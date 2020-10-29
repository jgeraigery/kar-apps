package com.ibm.research.kar.reefer.actors;

import com.ibm.research.kar.Kar;

import static com.ibm.research.kar.Kar.actorRef;
import static com.ibm.research.kar.Kar.restPost;
import static com.ibm.research.kar.Kar.restGet;
import static com.ibm.research.kar.Kar.actorCall;

import com.ibm.research.kar.actor.ActorRef;

import java.util.HashMap;
import java.util.Map;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.json.*;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.ibm.research.kar.actor.annotations.Activate;
import com.ibm.research.kar.actor.annotations.Actor;
import com.ibm.research.kar.actor.annotations.Deactivate;
import com.ibm.research.kar.actor.annotations.Remote;
import com.ibm.research.kar.actor.exceptions.ActorMethodNotFoundException;
import com.ibm.research.kar.reefer.ReeferAppConfig;
import com.ibm.research.kar.reefer.common.Constants;
import com.ibm.research.kar.reefer.common.json.JsonUtils;
import com.ibm.research.kar.reefer.model.*;
import com.ibm.research.kar.reefer.common.time.TimeUtils;

@Actor
public class VoyageActor extends BaseActor {
    private JsonObject voyageInfo;
    private JsonValue voyageStatus;
    private Map<String, String> orders = new HashMap<>();
    private static final Logger logger = Logger.getLogger(VoyageActor.class.getName());

    /**
     * Fetch actor's state from Kar persistent storage. On the first invocation call REST
     * to get Voyage info which includes details like daysAtSea, departure
     * date, arrival date, etc. Store it in Kar persistent storage for reuse on subsequent invocations.
     */
    @Activate
    public void init() {
        // fetch actor state from Kar storage
        Map<String, JsonValue> state = Kar.actorGetAllState(this);
        logger.info("VoyageActor.init() actorID:" + this.getId() + " all state" + state);
        try {
            // initial actor invocation should handle no state
            if (state.isEmpty()) {
                // call REST just once to get static voyage information like departure and arrival dates, etc
                Response response = restGet("reeferservice", "/voyage/info/" + getId());
                voyageInfo = response.readEntity(JsonValue.class).asJsonObject();
                // store static voyage information in Kar storage for reuse
                super.set(this, Constants.VOYAGE_INFO_KEY, voyageInfo);
            } else {

                if (state.containsKey(Constants.VOYAGE_INFO_KEY)) {
                    voyageInfo = state.get(Constants.VOYAGE_INFO_KEY).asJsonObject();
                }
                if (state.containsKey(Constants.VOYAGE_STATUS_KEY)) {
                    voyageStatus = state.get(Constants.VOYAGE_STATUS_KEY);
                }
                JsonValue jv = state.get(Constants.VOYAGE_ORDERS_KEY);
                if (jv != null) {
                    // since we already have all orders by calling actorGetAllState() above we can
                    // deserialize them using Jackson's ObjectMapper. Alternatively, one can
                    // use Kar.actorSubMapGet() which is an extra call.
                    ObjectMapper mapper = new ObjectMapper();
                    // deserialize json orders into a HashMap
                    orders = mapper.readValue(jv.toString(), HashMap.class);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "", e);
        }
    }

    /**
     * Save actor's state when the instance is passivated. Currently just saves the
     * actor's status.
     */
    @Deactivate
    public void deactivate() {
        if (voyageStatus != null) {
            super.set(this, Constants.VOYAGE_STATUS_KEY, voyageStatus);
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
        logger.info("VoyageActor.changePosition() called Id:" + getId() + " " + message.toString() + " state:"
                + getVoyageStatus());
        try {
            Voyage voyage = JsonUtils.jsonToVoyage(voyageInfo);
            // the simulator advances ship position
            int daysAtSea = message.getInt(Constants.VOYAGE_DAYSATSEA_KEY);
            // given ship sail date and current days at sea get ship's current date
            Instant shipCurrentDate = TimeUtils.getInstance().futureDate(voyage.getSailDateObject(), daysAtSea);
            logger.info(
                    "VoyageActor.changePosition() voyage info:" + voyageInfo + " ship current date:" + shipCurrentDate);
            String restMethodToCall = "";
            // if ship's current date matches arrival date, the ship arrived
            if (shipArrived(shipCurrentDate, voyage)) {
                voyageStatus = Json.createValue(VoyageStatus.ARRIVED.name());
                long snapshot = System.nanoTime();
                processArrivedVoyage(voyage, daysAtSea);
                logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId() + " order count: " +
                        orders.size() + " arrival processing: " + (System.nanoTime() - snapshot) / 1000000);
            } else if (shipDeparted(daysAtSea) && !VoyageStatus.DEPARTED.equals(getVoyageStatus())) {
                voyageStatus = Json.createValue(VoyageStatus.DEPARTED.name());
                long snapshot = System.nanoTime();
                processDepartedVoyage(voyage, daysAtSea);
                logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId() + " order count: " +
                        orders.size() + " departure processing: " + (System.nanoTime() - snapshot) / 1000000);
            } else {  // voyage in transit
                logger.info("VoyageActor.changePosition() Updating REST - daysAtSea:" + daysAtSea);
                // update REST voyage days at sea
                messageRest("/voyage/update/position", daysAtSea);
            }
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK).build();
        } catch (Exception e) {
            logger.log(Level.WARNING, "", e);
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, "FAILED").add("ERROR", "Exception")
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }

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
        JsonOrder order = new JsonOrder(message.getJsonObject(JsonOrder.OrderKey));

        logger.info("VoyageActor.reserve() called Id:" + getId() + " " + message.toString() + " OrderID:"
                + order.getId() + " Orders size=" + orders.size());

        try {
            // Book reefers for this order through the ReeferProvisioner
            JsonValue bookingStatus = actorCall(
                    actorRef(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId),
                    "bookReefers", message);
            if (reefersBooked(bookingStatus)) {
                // add new order to this voyage order list
                super.addToSubMap(this, Constants.VOYAGE_ORDERS_KEY, String.valueOf(order.getId()),
                        Json.createValue(order.getId()));
                orders.put(String.valueOf(order.getId()), String.valueOf((order.getId())));
                // reload order map since there is a change. Local orders map is not mutable
                voyageStatus = Json.createValue(VoyageStatus.PENDING.name());
                return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                        .add(Constants.REEFERS_KEY, bookingStatus.asJsonObject().getJsonArray(Constants.REEFERS_KEY))
                        .add(JsonOrder.OrderKey, order.getAsObject()).build();
            } else {
                return bookingStatus.asJsonObject();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "", e);
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, "FAILED").add("ERROR", "Exception")
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }
    }

    /**
     * Calls REST and Order actors when a ship arrives at the destination port
     *
     * @param voyage    - Voyage info
     * @param daysAtSea - ship days at sea
     */
    private void processArrivedVoyage(Voyage voyage, int daysAtSea) {
        logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId()
                + " has ARRIVED ------------------------------------------------------");
        messageRest("/voyage/update/arrived", daysAtSea);
        // notify each order actor that the ship arrived
        orders.values().forEach(orderId -> {
            logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId()
                    + " Notifying Order Actor of arrival - OrderID:" + orderId);
            messageOrderActor("delivered", orderId);
        });
    }

    /**
     * Calls REST and Order actors when a ship departs from the origin port
     *
     * @param voyage    - Voyage info
     * @param daysAtSea - ship days at sea
     */
    private void processDepartedVoyage(Voyage voyage, int daysAtSea) {
        logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId()
                + " has DEPARTED ------------------------------------------------------");
        messageRest("/voyage/update/departed", daysAtSea);
        orders.values().forEach(orderId -> {
            logger.info("VoyageActor.changePosition() voyageId=" + voyage.getId()
                    + " Notifying Order Actor of departure - OrderID:" + orderId);
            messageOrderActor("departed", orderId);
        });

    }

    /**
     * Update REST with ship position
     *
     * @param methodToCall -  REST API to call
     * @param daysAtSea    - ship days at sea
     */
    private void messageRest(String methodToCall, int daysAtSea) {
        JsonObject params = Json.createObjectBuilder().add(Constants.VOYAGE_ID_KEY, getId()).add("daysAtSea", daysAtSea)
                .build();
        try {
            /// Notify REST of the position change
            restPost("reeferservice", methodToCall, params);
        } catch (Exception e) {
            logger.log(Level.WARNING, "", e);
        }
    }

    /**
     * Call OrderActor when a ship carrying the order either departs or arrives
     *
     * @param orderId      - order id
     * @param methodToCall - actor method to call
     */
    private void messageOrderActor(String methodToCall, String orderId) {
        ActorRef orderActor = Kar.actorRef(ReeferAppConfig.OrderActorName, orderId);
        JsonObject params = Json.createObjectBuilder().build();
        actorCall(orderActor, methodToCall, params);
    }

    /**
     * Check if ReeferProvisioner booked the reefers for this order.
     *
     * @param bookingStatus - reply from ReeferProvisioner containing reefer booking status
     * @return true if reefers booked, false otherwise
     */
    private boolean reefersBooked(JsonValue bookingStatus) {
        return bookingStatus.asJsonObject().getString(Constants.STATUS_KEY).equals(Constants.OK);
    }

    /**
     * Ship departs when its days at sea = 1
     *
     * @param daysAtSea - number of days at sea
     * @return true if the ship departed, false otherwise
     */
    private boolean shipDeparted(int daysAtSea) {
        return daysAtSea == 1;
    }

    /**
     * Converts voyage status from JsonValue to VoyageStatus
     *
     * @return VoyageStatus instance
     */
    private VoyageStatus getVoyageStatus() {
        if (voyageStatus == null) {
            return VoyageStatus.UNKNOWN;
        }
        return VoyageStatus.valueOf(((JsonString) voyageStatus).getString());
    }

    /**
     * Determines if ship arrived at the destination port or not. Ship arrives when
     * current date = scheduled shipArrivalDate
     *
     * @param shipCurrentDate - current date
     * @param voyage          - voyage info
     * @return - true if ship arrived, false otherwise
     */
    private boolean shipArrived(Instant shipCurrentDate, Voyage voyage) { // String shipArrivalDate, String voyageId) {
        Instant scheduledArrivalDate = Instant.parse(voyage.getArrivalDate());
        return ((shipCurrentDate.equals(scheduledArrivalDate)
                || shipCurrentDate.isAfter(scheduledArrivalDate) && !VoyageStatus.ARRIVED.equals(getVoyageStatus())));
    }

}