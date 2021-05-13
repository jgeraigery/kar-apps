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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.research.kar.Kar;
import com.ibm.research.kar.actor.ActorRef;
import com.ibm.research.kar.actor.annotations.Activate;
import com.ibm.research.kar.actor.annotations.Actor;
import com.ibm.research.kar.actor.annotations.Remote;
import com.ibm.research.kar.reefer.ReeferAppConfig;
import com.ibm.research.kar.reefer.common.Constants;
import com.ibm.research.kar.reefer.model.JsonOrder;
import com.ibm.research.kar.reefer.model.Order;
import com.ibm.research.kar.reefer.model.Order.OrderStatus;

import javax.json.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Actor
public class OrderActor extends BaseActor {
    // There are three properties we need to persist for each order:
    //     1. state: PENDING | BOOKED | INTRANSIT | SPOILT | DELIVERED
    //     2. voyageId : voyage id the order is assigned to

    // wrapper containing order state
    private Order order = null;
    private static final Logger logger = Logger.getLogger(OrderActor.class.getName());

    @Activate
    public void activate() {
        Map<String, JsonValue> state = Kar.Actors.State.getAll(this);
        try {
            // initial actor invocation should handle no state
            if (!state.isEmpty()) {
                JsonObjectBuilder job = Json.createObjectBuilder();
                job.add(Constants.ORDER_ID_KEY, getId()).
                        add(Constants.ORDER_STATUS_KEY, state.get(Constants.ORDER_STATUS_KEY)).
                        add(Constants.VOYAGE_ID_KEY, state.get(Constants.VOYAGE_ID_KEY)).
                        add(Constants.ORDER_PRODUCT_KEY, state.get(Constants.ORDER_PRODUCT_KEY)).
                        add(Constants.ORDER_PRODUCT_QTY_KEY, state.get(Constants.ORDER_PRODUCT_QTY_KEY)).
                        add(Constants.ORDER_CUSTOMER_ID_KEY,state.get(Constants.ORDER_CUSTOMER_ID_KEY)).
                        add(Constants.ORDER_DATE_KEY,state.get(Constants.ORDER_DATE_KEY)).
                        add(Constants.ORDER_SPOILT_KEY,state.get(Constants.ORDER_SPOILT_KEY));

                order = new Order(job.build());
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine(String.format("OrderActor.activate() - orderId: %s state: %s voyageId: %s ",
                            getId(), order.getStatus(), order.getVoyageId()));
                }
            }
        } catch( Exception e) {
            e.printStackTrace();
        }


    }
    @Remote JsonObject state() {
        /*
        if ( orderState == null ) {
            activate();
        }
        return orderState.toJsonObject();

         */
        if ( order == null ) {
            activate();
        }
        return order.getAsJsonObject();
    }
    /**
     * Called to book a new order using properties included in the message. Calls the VoyageActor
     * to allocate reefers and a ship to carry them.
     *
     * @param message Order properties
     * @return
     */
    @Remote
    public JsonObject createOrder(JsonObject message) {
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(String.format("OrderActor.createOrder() - orderId: %s message: %s", getId(), message));
        }
        System.out.println(String.format("OrderActor.createOrder() - orderId: %s message: %s", getId(), message));
        // Idempotence check. Check if this order has already been booked.
        /*
        if (orderState != null && OrderStatus.BOOKED.name().equals(orderState.getStateAsString())) {
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }

         */
        if (order != null && OrderStatus.BOOKED.name().equals(order.getStatus())) {
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }
        try {
            // Java wrapper around Json payload
            //JsonOrder jsonOrder = new JsonOrder(message.getJsonObject(JsonOrder.OrderKey));
            order = new Order(message);
            // Call Voyage actor to book the voyage for this order. This call also
            // reserves reefers
            JsonObject voyageBookingResult = bookVoyage(order); //bookVoyage(jsonOrder);
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(String.format("OrderActor.createOrder() - orderId: %s VoyageActor reply: %s", getId(), voyageBookingResult));
            }
            // Check if voyage has been booked
            if (voyageBookingResult.containsKey(Constants.STATUS_KEY) && voyageBookingResult.getString(Constants.STATUS_KEY).equals(Constants.OK)) {
                /*
                orderState = new Order(Json.createObjectBuilder().
                        add(Constants.ORDER_STATUS_KEY, OrderStatus.BOOKED.name()).
                        add(Constants.VOYAGE_ID_KEY, Json.createValue(jsonOrder.getVoyageId())).build());
                JsonObjectBuilder jb = Json.createObjectBuilder();
                jb.add(Constants.VOYAGE_ID_KEY, orderState.getVoyageId()).
                        add(Constants.ORDER_PRODUCT_KEY,jsonOrder.getProduct()).
                        add(Constants.ORDER_PRODUCT_QTY_KEY, jsonOrder.getProductQty()).
                        add(Constants.ORDER_CUSTOMER_ID_KEY, jsonOrder.getCustomerId()).
                        add(Constants.ORDER_STATUS_KEY, orderState.getState());

                 */
              //  order = new Order(Json.createObjectBuilder().
             //           add(Constants.ORDER_STATUS_KEY, OrderStatus.BOOKED.name()).
            //            add(Constants.VOYAGE_ID_KEY, Json.createValue(jsonOrder.getVoyageId())).build());
               /*
                JsonObjectBuilder jb = Json.createObjectBuilder();
                jb.add(Constants.VOYAGE_ID_KEY, order.getVoyageId()).
                        add(Constants.ORDER_PRODUCT_KEY,jsonOrder.getProduct()).
                        add(Constants.ORDER_PRODUCT_QTY_KEY, jsonOrder.getProductQty()).
                        add(Constants.ORDER_CUSTOMER_ID_KEY, jsonOrder.getCustomerId()).
                        add(Constants.ORDER_STATUS_KEY, order.getStatus());
                Kar.Actors.State.set(this, jb.build());

                */
                Kar.Actors.State.set(this,order.getAsJsonObject());
                messageOrderManager("orderBooked");
                //ActorRef orderActor = Kar.Actors.ref(ReeferAppConfig.OrderManagerActorName, ReeferAppConfig.OrderManagerId);
               // Kar.Actors.tell(orderActor, "orderCreated", jb.build());

            } else {
                System.out.println("OrderActor.createOrder() - !!!!!!!!!!!!!!!!!!!! DELETING ORDER - "+getId() +" Order Booking Failed -"+voyageBookingResult);
                Kar.Actors.remove(this);
            }
            return voyageBookingResult;
        } catch (Exception e) {
            logger.log(Level.WARNING, "OrderActor.createOrder() - Error - orderId " + getId() + " ", e);
            return Json.createObjectBuilder().add(Constants.STATUS_KEY, "FAILED").add("ERROR", e.getMessage())
                    .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
        }
    }
    private void messageOrderManager(String methodToCall) {
        /*
        JsonObjectBuilder jb = Json.createObjectBuilder();
        jb.add(Constants.ORDER_ID_KEY, this.getId()).
                add(Constants.VOYAGE_ID_KEY, order.getVoyageId()).
                add(Constants.ORDER_PRODUCT_KEY,order.getProduct()).
                add(Constants.ORDER_PRODUCT_QTY_KEY, order.getProductQty()).
                add(Constants.ORDER_CUSTOMER_ID_KEY, order.getCustomerId()).
                add(Constants.ORDER_STATUS_KEY, order.getStatus());

         */
       // Kar.Actors.State.set(this, jb.build());
        ActorRef orderActor = Kar.Actors.ref(ReeferAppConfig.OrderManagerActorName, ReeferAppConfig.OrderManagerId);
        Kar.Actors.tell(orderActor, methodToCall, order.getAsJsonObject());
       // Kar.Actors.tell(orderActor, methodToCall, jb.build());
    }
    /**
     * Called when an order is delivered (ie.ship arrived at the destination port).
     *
     * @return
     */
    @Remote
    public JsonObject delivered() {
        /*
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(String.format("OrderActor.delivered() -  orderId: %s voyageId: %s reefers: %d ",
                    getId(), order.getVoyageId(), order.getReeferMap() == null ? 0 : orderState.getReeferMap().size()));
        }

         */
        messageOrderManager("orderArrived");
        Kar.Actors.remove(this);
        return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
    }

    /**
     * Called when ship departs from an origin port.
     *
     * @return
     */
    @Remote
    public JsonObject departed() {
        System.out.println("OrderActor.departed() ----------------"+getId());
        if (order != null && !OrderStatus.DELIVERED.name().equals(order.getStatus())) {
            saveOrderStatusChange(OrderStatus.INTRANSIT);
            messageOrderManager("orderDeparted");
        }
        return Json.createObjectBuilder().add(Constants.STATUS_KEY, Constants.OK)
                .add(Constants.ORDER_ID_KEY, String.valueOf(this.getId())).build();
    }

    /**
     * Called when a reefer anomaly is detected.
     * If order in transit, call provisioner to mark reefer spoilt
     * else call provisioner to request replacement reefer
     *
     * @param message - json encoded message
     */
    @Remote
    public void anomaly(JsonObject message) {
        int spoiltReeferId = message.getInt(Constants.REEFER_ID_KEY);
        if (logger.isLoggable(Level.FINE)) {
            logger.fine("OrderActor.anomaly() - :" + getId() + "received spoilt reefer ID " + spoiltReeferId);
        }
        /*
        // handle anomaly after ship arrival.
        if (orderState == null || orderState.getState() == null ||
                OrderStatus.DELIVERED.name().equals(orderState.getStateAsString())) {
            // Race condition
            if (logger.isLoggable(Level.INFO)) {
                logger.info("OrderActor.anomaly() - anomaly just arrived after order delivered");
            }
            // this actor should not be alive
            Kar.Actors.remove(this);
            return;
        }
        switch (OrderStatus.valueOf(orderState.getStateAsString())) {
            case INTRANSIT:
                // change state to Spoilt and inform provisioner
                tagAsSpoilt(message);
                break;
            case BOOKED:
                // ship hasn't left the origin port, request replacement
                requestReplacementReefer(message);
                break;
            default:
        }

         */
        // handle anomaly after ship arrival.
        if (order == null || order.getStatus() == null ||
                OrderStatus.DELIVERED.name().equals(order.getStatus())) {
            // Race condition
            if (logger.isLoggable(Level.INFO)) {
                logger.info("OrderActor.anomaly() - anomaly just arrived after order delivered");
            }
            // this actor should not be alive
            Kar.Actors.remove(this);
            return;
        }
        switch (OrderStatus.valueOf(order.getStatus())) {
            case INTRANSIT:
                // change state to Spoilt and inform provisioner
                tagAsSpoilt(message);
                break;
            case BOOKED:
                // ship hasn't left the origin port, request replacement
                requestReplacementReefer(message);
                break;
            default:
        }
    }

    /**
     * Calls provisioner to tag a given reefer as spoilt.
     *
     * @param message
     */
    private void tagAsSpoilt(JsonObject message) {
        /*
        int spoiltReeferId = message.getInt(Constants.REEFER_ID_KEY);
        if (!OrderStatus.SPOILT.name().equals(orderState.getStateAsString())) {
            saveOrderStatusChange(OrderStatus.SPOILT);
        }
        if (logger.isLoggable(Level.INFO)) {
            logger.info(String.format("OrderActor.tagAsSpoilt() - orderId: %s state: %s", getId(),
                    orderState.getState()));
        }
        ActorRef provisioner = Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId);
        JsonObject reply = Kar.Actors.call(provisioner, "reeferSpoilt", message).asJsonObject();
        if (reply.getString(Constants.STATUS_KEY).equals(Constants.FAILED)) {
            logger.warning("OrderActor.anomaly() - orderId " + getId() + " request to mark reeferId "
                    + spoiltReeferId + " spoilt failed");
        }

         */
        int spoiltReeferId = message.getInt(Constants.REEFER_ID_KEY);
        if (!OrderStatus.SPOILT.name().equals(order.getStatus())) {
            saveOrderStatusChange(OrderStatus.SPOILT);
        }
        if (logger.isLoggable(Level.INFO)) {
            logger.info(String.format("OrderActor.tagAsSpoilt() - orderId: %s state: %s", getId(),
                    order.getStatus()));
        }
        ActorRef provisioner = Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId);
        JsonObject reply = Kar.Actors.call(provisioner, "reeferSpoilt", message).asJsonObject();
        if (reply.getString(Constants.STATUS_KEY).equals(Constants.FAILED)) {
            logger.warning("OrderActor.anomaly() - orderId " + getId() + " request to mark reeferId "
                    + spoiltReeferId + " spoilt failed");
        }
    }

    /**
     * Calls provisioner to request a replacement reefer. Reefer can be replaced if
     * an order is booked but not yet departed.
     *
     * @param message
     */
    private void requestReplacementReefer(JsonObject message) {
        int spoiltReeferId = message.getInt(Constants.REEFER_ID_KEY);
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(String.format("OrderActor.requestReplacementReefer() - orderId: %s requesting replacement for %s",
                    getId(), message.getInt(Constants.REEFER_ID_KEY)));
        }
        ActorRef provisioner = Kar.Actors.ref(ReeferAppConfig.ReeferProvisionerActorName, ReeferAppConfig.ReeferProvisionerId);
        JsonObject reply = Kar.Actors.call(provisioner, "reeferReplacement", message).asJsonObject();
        if (reply.getString(Constants.STATUS_KEY).equals(Constants.FAILED)) {
            logger.warning("OrderActor.requestReplacementReefer() - orderId: " + getId()
                    + " request to replace reeferId " + spoiltReeferId + " failed");
            return;
        }

        int replacementReeferId = reply.getInt(Constants.REEFER_REPLACEMENT_ID_KEY);
        if (logger.isLoggable(Level.INFO)) {
            logger.info(String.format(
                    "OrderActor.requestReplacementReefer() - orderId: %s state: %s spoilt reefer id: %s replacement reefer id: %s",
                    getId(), order.getStatus(), spoiltReeferId, replacementReeferId));
        }
    }

    private void saveOrderStatusChange(OrderStatus state) {
        JsonValue jv = Json.createValue(state.name());
       // orderState.newState(jv);
       // order.newState(jv);
        order.setStatus(state.name());
        Kar.Actors.State.set(this, Constants.ORDER_STATUS_KEY, Json.createValue(order.getStatus()));
    }

    /**
     * Called to book voyage for this order by messaging Voyage actor.
     *
     * @param order Json encoded order properties
     * @return The voyage booking result
     */
   // private JsonObject bookVoyage(JsonOrder order) {
    private JsonObject bookVoyage(Order order) {
        /*
        JsonObject params =
                Json.createObjectBuilder().
                        add(JsonOrder.OrderKey, order.getAsObject()).
                        build();

         */
        ActorRef voyageActor = Kar.Actors.ref(ReeferAppConfig.VoyageActorName, order.getVoyageId());
       // return Kar.Actors.call(voyageActor, "reserve", params).asJsonObject();
        return Kar.Actors.call(voyageActor, "reserve", order.getAsJsonObject()).asJsonObject();
    }

    /**
     * Convenience class to hold order actor state
     */
    /*
    private class Order {
        JsonValue state = null;
        JsonValue voyageId = null;
        JsonValue product = null;
        JsonValue productQty = null;
        JsonValue customerId = null;
        Map<String, String> reeferMap = null;

        public Order(Map<String, JsonValue> allState) {
            try {
                this.state = allState.get(Constants.ORDER_STATUS_KEY);
                this.voyageId = allState.get(Constants.VOYAGE_ID_KEY);
                this.product = allState.get(Constants.ORDER_PRODUCT_KEY);
                this.productQty = allState.get(Constants.ORDER_PRODUCT_QTY_KEY);
                this.customerId = allState.get(Constants.ORDER_CUSTOMER_ID_KEY);
                if (allState.containsKey(Constants.REEFER_MAP_KEY)) {
                    JsonValue jv = allState.get(Constants.REEFER_MAP_KEY);
                    // since we already have all reefers by calling actorGetAllState() above we can
                    // deserialize them using Jackson's ObjectMapper. Alternatively, one can
                    // use Kar.actorSubMapGet() which is an extra call.
                    ObjectMapper mapper = new ObjectMapper();
                    // deserialize json reefers into a HashMap
                    Map<String, String> reeferMap = mapper.readValue(jv.toString(), HashMap.class);
                    this.addReefers(reeferMap);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "", e);
            }

        }

        public Order(JsonObject jo) {
            this.state = jo.getJsonString(Constants.ORDER_STATUS_KEY);
            this.voyageId = jo.getJsonString(Constants.VOYAGE_ID_KEY);
        }

        public JsonValue getState() {
            return state;
        }

        public String getStateAsString() {
            return ((JsonString) orderState.getState()).getString();
        }

        public JsonValue getVoyageId() {
            return voyageId;
        }

        public JsonValue getCustomerId() {
            return customerId;
        }

        public JsonValue getProduct() {
            return product;
        }

        public JsonValue getProductQty() {
            return productQty;
        }

        public Map<String, String> getReeferMap() {
            return reeferMap;
        }

        public void addReefers(Map<String, String> reeferMap) {
            this.reeferMap = reeferMap;
        }

        public void addReefer(int reeferId) {
            if (this.reeferMap == null) {
                this.reeferMap = new HashMap<>();
            }
            this.reeferMap.put(String.valueOf(reeferId), String.valueOf(reeferId));
        }

        public void replaceReefer(int reeferId, int replacementReeferId) {
            this.reeferMap.remove(String.valueOf(reeferId));
            this.addReefer(replacementReeferId);
        }

        public void newState(JsonValue state) {
            this.state = state;
        }

        public JsonObject toJsonObject() {
            return Json.createObjectBuilder().
                    add(Constants.ORDER_ID_KEY, getId()).
                    add(Constants.ORDER_STATUS_KEY, getState()).
                    add(Constants.VOYAGE_ID_KEY, getVoyageId()).
                    add(Constants.ORDER_PRODUCT_KEY, getProduct()).
                    add(Constants.ORDER_CUSTOMER_ID_KEY, getCustomerId()).
                    add(Constants.ORDER_PRODUCT_QTY_KEY, getProductQty()).
                    build();
        }
    }

     */
}