package com.ibm.research.kar.reeferserver.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import org.springframework.beans.factory.annotation.Autowired;

import com.ibm.research.kar.reeferserver.error.VoyageNotFoundException;
import com.ibm.research.kar.reefer.model.*;
import com.ibm.research.kar.reeferserver.scheduler.*;
import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

import com.ibm.research.kar.reefer.common.error.RouteNotFoundException;
import com.ibm.research.kar.reefer.common.error.ShipCapacityExceeded;
import com.ibm.research.kar.reefer.common.time.TimeUtils;
@Component
public class ScheduleService {

    private static final int THRESHOLD_IN_DAYS = 100;

    @Value("classpath:routes.json")
    private Resource routesJsonResource;

    @Autowired
    private ShippingScheduler scheduler;
    
    private LinkedList<Voyage> masterSchedule = new LinkedList<>();
    private List<Route> routes = new ArrayList<>();

    public List<Route> getRoutes() {
    
        try {
            routes = scheduler.getRoutes(routesJsonResource.getInputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return routes;

    }
    public void generateNextSchedule(Instant date) {
        System.out.println("ScheduleService() - generateNextSchedule() ");
        // generate future schedule if number of days from a given date and the last voyage in the
        // current master schedule is less than a threshold. 
        try {
            if ( routes.isEmpty()) {
                getRoutes();
            }
            Instant routeLastArrivalDate = getLastVoyageDateForRoute(routes.get(0));
            System.out.println("ScheduleService() - generateNextSchedule() routeLastArrivalDate="+routeLastArrivalDate.toString());
            if ( TimeUtils.getInstance().getDaysBetween(date, routeLastArrivalDate) < THRESHOLD_IN_DAYS ) {
                System.out.println("ScheduleService() - generateNextSchedule()  !!!!!!!!!!!! Replenishing Master Schedule with new voyages");
                // generate new schedule for the next year starting at routeLastArrivalDate
                masterSchedule.addAll(scheduler.generateSchedule(routeLastArrivalDate));
            }
        } catch( RouteNotFoundException e) {
            e.printStackTrace();
        }catch( Exception e) {
            e.printStackTrace();
        }
 
    }
    public Instant getLastVoyageDateForRoute(Route route)  throws RouteNotFoundException {
        
        ListIterator<Voyage> it = masterSchedule.listIterator(masterSchedule.size());
        Instant lastVoyageArrivalDate;
        while (it.hasPrevious()) {
            Voyage voyage = it.previous();
            // Find the last return trip for a given route
            if ( voyage.getRoute().getVessel().getName().equals(route.getVessel().getName())) {
                lastVoyageArrivalDate = Instant.parse(voyage.getArrivalDate());
                return TimeUtils.getInstance().futureDate(lastVoyageArrivalDate, route.getDaysAtPort());
            }
        }
        throw new RouteNotFoundException("Unable to find the last voyage for vessel:"+route.getVessel().getName());
    }
    public void updateDaysAtSea(String voyageId, int daysOutAtSea) {
        for( Voyage voyage : masterSchedule ) {
           // System.out.println("ScheduleService.updateDaysAtSea() - daysOutAtSea:"+daysOutAtSea);
            if ( voyage.getId().equals(voyageId)) {
                voyage.getRoute().getVessel().setPosition(daysOutAtSea);
                int progress = Math.round((daysOutAtSea/(float)voyage.getRoute().getDaysAtSea())*100);
                voyage.getRoute().getVessel().setProgress(progress);
                System.out.println("ScheduleService.updateDaysAtSea() - voyage:"+voyage.getId()+"daysOutAtSea:"+
                voyage.getRoute().getVessel().getPosition()+" Progress:"+
                voyage.getRoute().getVessel().getProgress());
                break;
            }
        }
    }
    public List<Voyage> getMatchingSchedule( Instant startDate, Instant endDate) {
        List<Voyage> schedule = new ArrayList<>();
        if ( masterSchedule.isEmpty() ) {
            try {
                scheduler.initialize(routesJsonResource.getInputStream());
                masterSchedule =scheduler.generateSchedule();
            } catch( Exception e) {
                // !!!!!!!!!!!!!!!!!!!!!! HANDLE THIS
                e.printStackTrace();
            }
        }
        for( Voyage voyage : masterSchedule ) {
            if ( (voyage.getSailDateObject().equals(startDate) || voyage.getSailDateObject().isAfter(startDate)) &&
                 (voyage.getSailDateObject().equals(endDate)  || voyage.getSailDateObject().isBefore(endDate) ) ) {
                    schedule.add(voyage);
            }
        }

        return schedule;
    }
    public List<Voyage> getMatchingSchedule(String origin, String destination, Instant date) {
        List<Voyage> schedule = new ArrayList<>();
        if ( masterSchedule.isEmpty() ) {
            try {
                System.out.println("getMatchingSchedule - Master Schedule is empty, generating new one");
                scheduler.initialize(routesJsonResource.getInputStream());
                masterSchedule =scheduler.generateSchedule();
            } catch( Exception e) {
                // !!!!!!!!!!!!!!!!!!!!!! HANDLE THIS
                e.printStackTrace();
            }
        }
        for( Voyage voyage : masterSchedule ) {
 
            if ( (voyage.getSailDateObject().equals(date) ||
                 voyage.getSailDateObject().isAfter(date)) &&
                 voyage.getRoute().getOriginPort().equals(origin) &&
                 voyage.getRoute().getDestinationPort().equals(destination) ) {
                    schedule.add(voyage);
                    System.out.println("getMatchingSchedule - Found voyage id:"+voyage.getId()+" Free Capacity:"+voyage.getRoute().getVessel().getFreeCapacity());
            }

        }

        return schedule;
    }
    /*
        Returns voyages with ships currently at sea.
    */
    public List<Voyage> getActiveSchedule() {
        Instant currentDate = TimeUtils.getInstance().getCurrentDate();
        List<Voyage> activeSchedule = new ArrayList<>();
        if ( masterSchedule.isEmpty() ) {
            try {
                scheduler.initialize(routesJsonResource.getInputStream());
                masterSchedule =scheduler.generateSchedule();
            } catch( Exception e) {
                // !!!!!!!!!!!!!!!!!!!!!! HANDLE THIS
                e.printStackTrace();
            }
        }
        for( Voyage voyage : masterSchedule ) {
            Instant arrivalDate = 
              TimeUtils.getInstance().futureDate(voyage.getSailDateObject(), voyage.getRoute().getDaysAtSea());

            if ( voyage.getSailDateObject().isAfter(currentDate) ) {
                // masterSchedule is sorted by sailDate, so if voyage sailDate > currentDate
                // we just stop iterating since all voyagaes sail in the future.
                break;
            }
            System.out.println("getActiveSchedule() - CurrentDate: "+currentDate.toString()+ " Voyage "+voyage.getId()+
            " SailDate: "+
            voyage.getSailDate()+" ArrivalDate: "+arrivalDate.toString()); 
            // find active voyage which is one that started before current date and
            // has not yet completed
            if (TimeUtils.getInstance().isSameDay(voyage.getSailDateObject(), currentDate) ||
                (voyage.getSailDateObject().isBefore(currentDate) &&
                arrivalDate.isAfter(currentDate) ) ) {
                //    long noOfDaysBetween = ChronoUnit.DAYS.between(voyage.getSailDateObject(), currentDate);

                //    voyage.getRoute().getVessel().setPosition(noOfDaysBetween);
                //    int progress = Math.round((noOfDaysBetween/(float)voyage.getRoute().getDaysAtSea())*100);
                //    voyage.getRoute().getVessel().setProgress(progress);
                activeSchedule.add(voyage);
            }
        }
        return activeSchedule;
   }

    public List<Voyage> get() {
         try {
            scheduler.initialize(routesJsonResource.getInputStream());
        } catch (Exception e) {
            e.printStackTrace();
        }

        LinkedList<Voyage> sortedSchedule = scheduler.generateSchedule();
        if ( masterSchedule.size() == 0 ) {
            masterSchedule.addAll(sortedSchedule);
        } else {
            Voyage lastVoyageFromMasterSchedule = masterSchedule.getLast();
            
            for ( Voyage voyage : sortedSchedule ) {
                if ( voyage.getSailDateObject().isAfter(lastVoyageFromMasterSchedule.getSailDateObject())) {
                    masterSchedule.add(voyage);
                }
            }
        }
        
        return new ArrayList<Voyage>(sortedSchedule);
    }
    public Voyage getVoyage(String voyageId) throws VoyageNotFoundException {
        for( Voyage voyage : masterSchedule ) {
            if ( voyage.getId().equals(voyageId)) {
                return voyage;
            }
        }
        throw new VoyageNotFoundException("Unable to find voyage with ID:"+voyageId);
    }

    public int updateFreeCapacity(String voyageId, int reeferCount) throws VoyageNotFoundException, ShipCapacityExceeded {
        Voyage voyage = getVoyage(voyageId);
        if ( voyage.getRoute().getVessel().getFreeCapacity() - reeferCount >= 0 ) {
            voyage.getRoute().getVessel().setFreeCapacity(voyage.getRoute().getVessel().getFreeCapacity()- reeferCount);
            return voyage.getRoute().getVessel().getFreeCapacity();
        }
        throw new ShipCapacityExceeded("VoyageID:"+voyageId+" Unable to book ship due to lack of capacity. Current capacity:"+voyage.getRoute().getVessel().getFreeCapacity()+" Order reefer count:"+reeferCount);
    }
    
}