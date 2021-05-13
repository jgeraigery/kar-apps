/*
 * Copyright IBM Corporation 2020,2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.research.kar.reefer.actors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.research.kar.Kar;
import com.ibm.research.kar.actor.ActorRef;
import com.ibm.research.kar.actor.annotations.Activate;
import com.ibm.research.kar.actor.annotations.Actor;
import com.ibm.research.kar.actor.annotations.Remote;
import com.ibm.research.kar.reefer.ReeferAppConfig;
import com.ibm.research.kar.reefer.common.Constants;
import com.ibm.research.kar.reefer.common.ReeferAllocator;
import com.ibm.research.kar.reefer.common.json.VoyageJsonSerializer;
import com.ibm.research.kar.reefer.common.time.TimeUtils;
import com.ibm.research.kar.reefer.model.JsonOrder;
import com.ibm.research.kar.reefer.model.Order;
import com.ibm.research.kar.reefer.model.Voyage;
import com.ibm.research.kar.reefer.model.VoyageStatus;

import javax.json.*;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

@Actor
public class VoyageActor extends BaseActor {
    private JsonObject voyageInfo;
    private Voyage voyage = null;
    private JsonValue voyageStatus;
    private Map<String, JsonValue> orders = new HashMap<>();
    private static final Logger logger = Logger.getLogger(VoyageActor.class.getName());

    /**
     * Fetch actor's state from Kar persistent storage. On the first invocation call REST
     * to get Voyage info which includes details like daysAtSea, departure
     * date, arrival date, etc. Store it in Kar persistent storage for reuse on subsequent invocations.
     */
    @Activate
    public void activate() {
        // fetch actor state from Kar storage
        Map<String, JsonValue> state = Kar.Actors.State.getAll(this);
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("VoyageActor.init() actorID:" + this.getId() + " all state" + state);
        }


        // initial actor invocation should handle no state
        if (state.isEmpty()) {
            // call REST just once to get static voyage information like departure and arrival dates, etc
            Response response = Kar.Services.get("reeferservice", "/voyage/info/" + getId());
            voyageInfo = response.readEntity(JsonValue.class).asJsonObject();
            // store voyage information in Kar storage for reuse
            Kar.Actors.State.set(this, Constants.VOYAGE_INFO_KEY, voyageInfo);
        } else {

            if (state.containsKey(Constants.VOYAGE_INFO_KEY)) {
                voyageInfo = state.get(Constants.VOYAGE_INFO_KEY).asJsonObject();
            }
            if (state.containsKey(Constants.VOYAGE_STATUS_KEY)) {
                voyageStatus = state.get(Constants.VOYAGE_STATUS_KEY);
            }
            restoreOrdersMap(state);
        }
        voyage = VoyageJsonSerializer.deserialize(voyageInfo);
    }
    private void restoreOrdersMap(Map<String, JsonValue> state) {
        if (state.containsKey(Constants.VOYAGE_ORDERS_KEY)) {
            JsonValue jv = state.get(Constants.VOYAGE_ORDERS_KEY);
            // since we already have all orders by calling actorGetAllState() above we can
            // deserialize them using Jackson's ObjectMapper. Alternatively, one can
            // use Kar.actorSubMapGet() which is an extra call.
            ObjectMapper mapper = new ObjectMapper();
            try {
                // deserialize json orders into a HashMap
                orders = mapper.readValue(jv.toString(), HashMap.class);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Called on ship position change. Determines if the ship departed from
     * its origin port or arrived at the destination. Updates REST ship
     * position.
     *
     * @param message - Json encoded message containing daysAtSea value
     * @return
     */
    @Remote
    public JsonValue changePosition(JsonObject message) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info("VoyageActor.changePosition() called Id:" + getId() + " " + message.toString() + " state:"
                    + getVoyageStatus());
        }
        if (Objects.isNull(voyage)) {
            logger.log(Level.WARNING, "VoyageActor.changePosition() - Error - voyageId " + getId() + " metadata is not defined - looks like the REST service is down");
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, "FAILED").add("ERROR", "Rest Service Unavailable - voyage metadata unknown")
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }

        // the simulator advances ship position
        int daysOutAtSea = message.getInt(Constants.VOYAGE_DAYSATSEA_KEY);
        // process only if the position has changed
        if (voyage.positionChanged(daysOutAtSea)) {
            // given ship sail date and current days at sea get ship's current date
            Instant shipCurrentDate = TimeUtils.getInstance().futureDate(voyage.getSailDateObject(), daysOutAtSea);
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(
                        "VoyageActor.changePosition() voyage info:" + voyageInfo + " ship current date:" + shipCurrentDate);
            }
            // if ship's current date matches (or exceeds) its arrival date, the ship arrived
            if (voyage.shipArrived(shipCurrentDate, getVoyageStatus())) {
                if (!VoyageStatus.DEPARTED.equals(getVoyageStatus()) ) {
                    logger.log(Level.WARNING, "VoyageActor.changePosition() - voyage:" + voyage.getId() + " arrived BUT its expected state is not DEPARTED. Instead it is " + getVoyageStatus());
                }
                voyageStatus = Json.createValue(VoyageStatus.ARRIVED.name());
                // notify voyage orders of arrival
                processArrivedVoyage(voyage, daysOutAtSea);
                // voyage arrived, no longer need the state
                Kar.Actors.remove(this);
            } else {
                JsonObjectBuilder jb = Json.createObjectBuilder();
                if (voyage.shipDeparted(shipCurrentDate, getVoyageStatus())) {
                    // notify voyage orders of departure
                    processDepartedVoyage(voyage, daysOutAtSea);
                    voyageStatus = Json.createValue(VoyageStatus.DEPARTED.name());
                    jb.add(Constants.VOYAGE_STATUS_KEY, voyageStatus);
                } else {  // voyage in transit
                    // update REST voyage days at sea
                    messageRest("/voyage/update/position", daysOutAtSea, Json.createArrayBuilder().build());
                    messageSchedulerActor("positionChanged", daysOutAtSea);
                }
                voyage.changePosition(daysOutAtSea);
                jb.add(Constants.VOYAGE_INFO_KEY, VoyageJsonSerializer.serialize(voyage));
                Kar.Actors.State.set(this, jb.build());
            }
        }
        return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK).build();

    }

    /**
     * Called to book a voyage for a given order. Calls ReeferProvisioner to book reefers and
     * stores orderId in the Kar persistent storage.
     *
     * @param message Json encoded order properties
     * @return - result of reefer booking
     */
    @Remote
    public JsonObject reserve(JsonObject message) {
        //JsonOrder order = new JsonOrder(message.getJsonObject(JsonOrder.OrderKey));
        // wrapper around Json
        Order order = new Order(message);
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("VoyageActor.reserve() called Id:" + getId() + " " + message.toString() + " OrderID:"
                    + order.getId() + " Orders size=" + orders.size());
        }
        JsonValue bookingStatus = null;
        // Idempotence check. If a given order is in this voyage order list it must have already been processed.
        if (orders.containsKey(order.getId())) {
            bookingStatus = orders.get(order.getId());
            // this order has already been processed so return result
            if (bookingStatus.asJsonObject().getString(Constants.STATUS_KEY).equals(Constants.OK)) {
                return buildResponse(bookingStatus, order, voyage.getRoute().getVessel().getFreeCapacity());
            }
            return orders.get(order.getId()).asJsonObject();
        }

        try {
            // Check if ship has capacity for the order.
            int howManyReefersNeeded = ReeferAllocator.howManyReefersNeeded(order.getProductQty());
            if (!voyage.capacityAvailable(howManyReefersNeeded) ) {
                String msg = "Error - ship capacity exceeded - current available capacity:" + voyage.getRoute().getVessel().getFreeCapacity() * 1000 +
                        " - reduce product quantity or choose a different voyage";
                return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.FAILED)
                        .add("ERROR", msg).build();
            }
            // Book reefers for this order through the ReeferProvisioner
            bookingStatus = Kar.Actors.call(Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId),
                    "bookReefers", message);
            // convenience wrapper for ReeferProvisioner json reply
            ReeferProvisionerReply reply = new ReeferProvisionerReply(bookingStatus);
            //System.out.println("VoyageActor.reserve() - Id:" + getId()+" ReeferProvisioner.book() reply:"+bookingStatus);
            if (reply.success()) {
                save(reply, order, bookingStatus);
                JsonObject params = Json.createObjectBuilder().
                        add(Constants.VOYAGE_ID_KEY, getId()).
                        add(Constants.VOYAGE_FREE_CAPACITY_KEY, voyage.getRoute().getVessel().getFreeCapacity()).
                        add(Constants.VOYAGE_ORDERS_KEY, orders.size()).
                        build();
                ActorRef orderActor = Kar.Actors.ref(ReeferAppConfig.ScheduleManagerActorName, ReeferAppConfig.ScheduleManagerId);
                Kar.Actors.tell(orderActor, "updateVoyage", params);
                return buildResponse(bookingStatus, order, voyage.getRoute().getVessel().getFreeCapacity());
            }
            // return failure
            return bookingStatus.asJsonObject();
        } catch (Exception e) {
            logger.log(Level.WARNING, "VoyageActor.reserve() - Error - voyageId " + getId() + " ", e);
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, "FAILED").add("ERROR", e.getMessage())
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }
    }
    private void save(ReeferProvisionerReply booking, Order order, JsonValue bookingStatus) {
        voyage.setReeferCount(voyage.getReeferCount() + booking.getReeferCount());
        voyage.updateFreeCapacity(booking.getReeferCount());
        if (logger.isLoggable(Level.INFO)) {
            logger.info("VoyageActor.reserve() - Vessel " + voyage.getRoute().getVessel().getName() + " Updated Free Capacity "
                    + voyage.getRoute().getVessel().getFreeCapacity());
        }
        voyageStatus = Json.createValue(VoyageStatus.PENDING.name());
        JsonObjectBuilder jb = Json.createObjectBuilder();
        jb.add(Constants.VOYAGE_STATUS_KEY, voyageStatus).
                add(Constants.VOYAGE_INFO_KEY, VoyageJsonSerializer.serialize(voyage));
        Kar.Actors.State.set(this, jb.build());
        // add new order to this voyage order list
        Kar.Actors.State.Submap.set(this, Constants.VOYAGE_ORDERS_KEY, String.valueOf(order.getId()),
                Json.createValue(order.getId()));
        orders.put(String.valueOf(order.getId()), bookingStatus);
        voyage.setOrderCount(orders.size());
    }
    private JsonObject buildResponse(final JsonValue bookingStatus, final Order order, final int freeCapacity) {
        return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                .add(JsonOrder.OrderKey, order.getAsJsonObject())
                .add(Constants.VOYAGE_FREE_CAPACITY_KEY, freeCapacity)
                .build();
    }

    /**
     * Calls REST and Order actors when a ship arrives at the destination port
     *
     * @param voyage    - Voyage info
     * @param daysAtSea - ship days at sea
     */
    private void processArrivedVoyage(final Voyage voyage, int daysAtSea) {
        if (logger.isLoggable(Level.INFO)) {
            logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId()
                    + " has ARRIVED ------------------------------------------------------");
        }
        try {
            // notify each order actor that the ship arrived
            orders.keySet().forEach(orderId -> {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("VoyageActor.changePosition() voyageId=" + voyage.getId()
                            + " Notifying Order Actor of arrival - OrderID:" + orderId);
                }
                messageOrderActor("delivered", orderId);
            });
            messageSchedulerActor("voyageDeparted", daysAtSea);
        } catch( Exception e) {
            logger.log(Level.WARNING, "VoyageActor.processArrivedVoyage() - Error while notifying order of arrival- voyageId " + getId() + " ", e);
        }
        JsonArray voyageOrders = null;
        if ( !orders.isEmpty()) {
           voyageOrders = Json.createArrayBuilder(orders.keySet()).build();
            Kar.Actors.call(Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId),
                    "releaseVoyageReefers",
                    Json.createObjectBuilder().add(Constants.VOYAGE_ORDERS_KEY, voyageOrders).build());
                   //  Json.createObjectBuilder().add(Constants.VOYAGE_ID_KEY, getId()).build());
        } else {
            voyageOrders = Json.createArrayBuilder().build();
        }

       // messageRest("/voyage/update/arrived", daysAtSea, voyageOrders);
    }

    /**
     * Calls REST and Order actors when a ship departs from the origin port
     *
     * @param voyage    - Voyage info
     * @param daysAtSea - ship days at sea
     */
    private void processDepartedVoyage(final Voyage voyage, int daysAtSea) {
        try {
            if (logger.isLoggable(Level.INFO)) {
                logger.info("VoyageActor.processDepartedVoyage() voyageId=" + voyage.getId()
                        + " has DEPARTED ------------------------------------------------------");
            }
            orders.keySet().forEach(orderId -> {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("VoyageActor.processDepartedVoyage() voyageId=" + voyage.getId()
                            + " Notifying Order Actor of departure - OrderID:" + orderId);
                }
                System.out.println("VoyageActor.processDepartedVoyage() voyageId=" + voyage.getId()
                        + " Notifying Order Actor of departure - OrderID:" + orderId);
                messageOrderActor("departed", orderId);
            });

            messageSchedulerActor("voyageDeparted", daysAtSea);
            ActorRef reeferProvisionerActor = Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName,
                    ReeferAppConfig.ReeferProvisionerId);
            JsonObject params = Json.createObjectBuilder().add(Constants.VOYAGE_ID_KEY, getId()).
                    add(Constants.VOYAGE_REEFERS_KEY, Json.createValue(voyage.getReeferCount())).
                    build();
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(
                        "VoyageActor.processDepartedVoyage() - calling ReeferProvisionerActor voyageReefersDeparted - voyageId:" + getId());
            }
            Kar.Actors.call(reeferProvisionerActor, "voyageReefersDeparted", params);
            JsonArray voyageOrders = null;
            if ( !orders.isEmpty()) {
                voyageOrders = Json.createArrayBuilder(orders.keySet()).build();
            } else {
                voyageOrders = Json.createArrayBuilder().build();
            }
          //  messageRest("/voyage/update/departed", daysAtSea, voyageOrders);
        } catch( Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * Update REST with ship position
     *
     * @param methodToCall -  REST API to call
     * @param daysAtSea    - ship days at sea
     */
    private void messageRest(String methodToCall, int daysAtSea, JsonArray voyageOrders) {
        JsonObject params = Json.createObjectBuilder().
                add(Constants.VOYAGE_ID_KEY, getId()).add("daysAtSea", daysAtSea).
                add(Constants.VOYAGE_ORDERS_KEY, voyageOrders).
                build();
        Kar.Services.post(Constants.REEFERSERVICE, methodToCall, params);
    }

    /**
     * Call OrderActor when a ship carrying the order either departs or arrives
     *
     * @param orderId      - order id
     * @param methodToCall - actor method to call
     */
    private void messageOrderActor(String methodToCall, String orderId) {
        ActorRef orderActor = Kar.Actors.ref(ReeferAppConfig.OrderActorName, orderId);
        Kar.Actors.call(orderActor, methodToCall);
    }
    /**
     * Call ScheduleManagerActor when a ship changes position
     *
     * @param daysOutAtSea     - ship position
     * @param methodToCall - actor method to call
     */
    private void messageSchedulerActor(String methodToCall, int daysOutAtSea ) {
        JsonObject params = Json.createObjectBuilder().
                add(Constants.VOYAGE_ID_KEY, getId()).add(Constants.VOYAGE_DAYSATSEA_KEY, daysOutAtSea).
                build();
        ActorRef orderActor = Kar.Actors.ref(ReeferAppConfig.ScheduleManagerActorName, ReeferAppConfig.ScheduleManagerId);
        Kar.Actors.tell(orderActor, methodToCall, params);
    }
    /**
     * Converts voyage status from JsonValue to VoyageStatus
     *
     * @return VoyageStatus instance
     */
    private VoyageStatus getVoyageStatus() {
        if (Objects.isNull(voyageStatus)) {
            return VoyageStatus.UNKNOWN;
        }
        return VoyageStatus.valueOf(((JsonString) voyageStatus).getString());
    }

    private class ReeferProvisionerReply {

        private JsonValue jv;
        private JsonValue reeferCount;

        protected ReeferProvisionerReply(JsonValue jsonReply) {
            jv = jsonReply;
        }

        protected boolean success() {
            return jv != null &&
                    jv.asJsonObject() != null &&
                    jv.asJsonObject().containsKey(Constants.STATUS_KEY) &&
                    jv.asJsonObject().getString(Constants.STATUS_KEY).equals(Constants.OK);
        }

        protected int getReeferCount() {
            if (reeferCount == null) {
                reeferCount = jv.asJsonObject().get(Constants.REEFERS_KEY);
            }
            return ((JsonNumber)reeferCount).intValue(); //reefers.size();
        }
    }
}