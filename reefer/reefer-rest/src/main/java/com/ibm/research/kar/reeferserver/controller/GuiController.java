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

package com.ibm.research.kar.reeferserver.controller;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ibm.research.kar.reefer.model.Order;
import com.ibm.research.kar.reefer.model.OrderStats;
import com.ibm.research.kar.reefer.model.Reefer;
import com.ibm.research.kar.reefer.model.ReeferStats;
import com.ibm.research.kar.reefer.model.Voyage;
import com.ibm.research.kar.reeferserver.model.ShippingSchedule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

/**
 * Updates the GUI using websockets. The order and reefer related counts are
 * updated at regular intervals via a TimerTask.
 */
@RestController
@CrossOrigin("*")
public class GuiController {
    @Autowired
    private SimpMessagingTemplate template;

    private AtomicInteger inTransitOrderCount = new AtomicInteger();
    private AtomicInteger futureOrderCount = new AtomicInteger();
    private AtomicInteger spoiltOrderCount = new AtomicInteger();
    private ReeferStats reeferStats;

    private AtomicBoolean valuesChanged = new AtomicBoolean();
    private static final Logger logger = Logger.getLogger(GuiController.class.getName());
    public GuiController() {

        TimerTask timerTask = new GuiUpdateTask();
        // running timer task as daemon thread. It updates
        // the GUI counts at regular intervals
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(timerTask, 0, 100);
    }

    public void sendActiveVoyageUpdate(List<Voyage> voyages, String currentDate) {
        long start = System.currentTimeMillis();
        ShippingSchedule schedule = new ShippingSchedule(voyages, currentDate);
        template.convertAndSend("/topic/voyages", schedule);
        long end = System.currentTimeMillis();
        if ( logger.isLoggable(Level.INFO)) {
            logger.info("GuiController.sendActiveVoyageUpdate() - voyage update took " + (end - start) + " ms");
        }
    }

    public void sendOrderUpdate(Order order) {

        template.convertAndSend("/topic/orders", order);
    }
    public void updateOrderCounts(OrderStats stats) {
        inTransitOrderCount.set(stats.getInTransitOrderCount());
        futureOrderCount.set(stats.getFutureOrderCount());
        spoiltOrderCount.set(stats.getSpoiltOrderCount());
        valuesChanged.set(true);
    }

    public void updateInTransitOrderCount(int orderCount) {
        inTransitOrderCount.set(orderCount);
        valuesChanged.set(true);
    }

    public void updateFutureOrderCount(int orderCount) {
        futureOrderCount.set(orderCount);
        valuesChanged.set(true);
    }

    public void updateSpoiltOrderCount(int orderCount) {
        spoiltOrderCount.set(orderCount);
        valuesChanged.set(true);
    }



    public void updateReeferStats(ReeferStats stats) {
        reeferStats = stats;
        valuesChanged.set(true);

    }

    private class GuiUpdateTask extends TimerTask {
        @Override
        public void run() {

            if (valuesChanged.get()) {
                OrderStats orderStats = new OrderStats(inTransitOrderCount.get(), futureOrderCount.get(),
                        spoiltOrderCount.get());
                 if (orderStats != null) {
                    template.convertAndSend("/topic/orders/stats", orderStats);
                }

                if (reeferStats != null) {
                    template.convertAndSend("/topic/reefers/stats", reeferStats);
                }
                valuesChanged.set(false);
            }

        }
    }
}