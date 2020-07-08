package com.ibm.research.kar.reeferserver.scheduler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.type.TypeReference;

import com.ibm.research.kar.reeferserver.model.*;
import com.ibm.research.kar.reeferserver.ReeferServerApplication;

public class ShippingScheduler {
    private LinkedList<Voyage> sortedSchedule = new LinkedList<Voyage>();
    private List<Route> routes = new ArrayList<Route>();
	private static TimeZone timeZone = TimeZone.getTimeZone("UTC");
	private static Calendar calendar = Calendar.getInstance(timeZone);
 
    public void initialize(InputStream routeConfigFile) throws Exception {

        ObjectMapper mapper = new ObjectMapper();
        TypeReference<List<Route>> typeReference = new TypeReference<List<Route>>() {
        };

        try {
            routes = mapper.readValue(routeConfigFile, typeReference);
            for (Route route : routes) {
                System.out.println("................Origin Port: " + route.getOriginPort() + " Ship:"
                        + route.getVessel().getName() + " Ship Capacity:" + route.getVessel().getMaxCapacity());
            }
        } catch (IOException e) {
            System.out.println("Unable to save users: " + e.getMessage());
        }

    }
    public List<Route> getRoutes(InputStream routeConfigFile)  throws Exception {
        if ( routes.isEmpty()) {
            this.initialize(routeConfigFile);
        }
        return routes;
    }
    public LinkedList<Voyage> generateSchedule() {
        return generateSchedule(ReeferServerApplication.getCurrentDate());
    }

    public LinkedList<Voyage> generateSchedule(Date departureDate) {
        // List<Route> schedule = new ArrayList<Route>();

        Date arrivalDate;
        // the shipping schedule is generated for one year from now
        int daysInCurrentYear = LocalDate.now().lengthOfYear();
        Date yearFromNow = getDate(departureDate, daysInCurrentYear);

        int staggerInitialShipDepartures = 0;

        for (Route route : routes) {
            System.out.println("ScheduleGenerator new route - from:" + route.getOriginPort() + " To:"
                    + route.getDestinationPort());
            // generate current ship schedule for the whole year
            while (departureDate.compareTo(yearFromNow) < 0) {
                // get the ship arrival date at destination port (departureDate+transitTime)
                arrivalDate = getDate(departureDate, route.getDaysAtSea());
                // add voyage to a sorted (by departure date) schedule
                addVoyageToSchedule(route, departureDate, false);
                // the ship returns back to origin port after it is unloaded and loaded up again
                departureDate = getDate(arrivalDate, route.getDaysAtPort());
                // add return voyage to a sorted (by departure date) schedule
                addVoyageToSchedule(route, departureDate, true);
                // calculate departure date for next voyage from origin to destination
                departureDate = getDate(departureDate, route.getDaysAtSea() + route.getDaysAtPort());
            }
            // initial ship departures staggered by 2 days (change this if necessary)
            staggerInitialShipDepartures += 2;
            // reset departure date to today+stagger (calculated above) so that the ships
            // dont depart on the same day
            departureDate = getDate(new Date(), staggerInitialShipDepartures);

        }
        System.out.println("ScheduleGenerator - generated:" + sortedSchedule.size() + " Voyages");
//        return new ArrayList<Voyage>(sortedSchedule);
        return sortedSchedule;
    }

    private void addVoyageToSchedule(Route route, Date departureDate, boolean returnVoyage) {
        // if ( sortedSchedule.size() > 0 ) {
        int next = 0;
        while (next < sortedSchedule.size()) {
            Voyage voyage = sortedSchedule.get(next);
            if (voyage.getSailDate().compareTo(departureDate) < 0) {
                next++;
            } else {
                break;
            }
        }
        sortedSchedule.add(next, newScheduledVoyage(route, departureDate, returnVoyage));
        // }
    }

    private Voyage newScheduledVoyage(Route route, Date departureDate, boolean returnVoyage) {
        Voyage voyage;

        if (returnVoyage) {
            // for return voyage reverse origin and destination ports
            voyage = new Voyage(new Route(route.getVessel(), route.getDestinationPort(), route.getOriginPort(),
                    route.getDaysAtSea(), route.getDaysAtPort()), departureDate);
        } else {
            voyage = new Voyage(route, departureDate);
        }
        return voyage;
    }

    public static final Date getDate(Date date, int days) {
        calendar.setTime(date);
        calendar.add(Calendar.DATE, days);
        return calendar.getTime();
    }

}