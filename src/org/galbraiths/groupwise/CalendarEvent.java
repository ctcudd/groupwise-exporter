package org.galbraiths.groupwise;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;

public class CalendarEvent {
    private Date eventStart;
    private Date eventStop;
    private String description;
    private String location;
    private List attendees;

    public CalendarEvent() {
        attendees = new ArrayList();
    }

    public Date getEventStart() {
        return eventStart;
    }

    public void setEventStart(Date eventStart) {
        this.eventStart = eventStart;
    }

    public Date getEventStop() {
        return eventStop;
    }

    public void setEventStop(Date eventStop) {
        this.eventStop = eventStop;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public List getAttendees() {
        return attendees;
    }
}
