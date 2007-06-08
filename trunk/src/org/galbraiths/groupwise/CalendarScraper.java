package org.galbraiths.groupwise;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;
import org.apache.log4j.BasicConfigurator;
import org.galbraiths.utils.StringUtils;
import org.htmlparser.Parser;
import org.htmlparser.Node;
import org.htmlparser.Tag;
import org.htmlparser.Text;
import org.htmlparser.util.NodeList;
import org.htmlparser.filters.TagNameFilter;
import org.htmlparser.tags.LinkTag;

import java.io.IOException;
import java.io.File;
import java.io.StringWriter;
import java.io.FileWriter;
import java.util.*;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.text.ParseException;

public class CalendarScraper {
    // I despise commons-logging but am using it as commons HTTP client uses it
    private static Log logger = LogFactory.getLog(CalendarScraper.class);

    /**
     * Converts the GroupWise 6.5 web interface into a series of {@link CalendarEvent}s.
     *
     * @param baseUrl   the base of all the requests, such as <code>https://web.mydomain.com/</code>. The trailing slash
     *                  is required.
     * @param username
     * @param password
     * @return
     * @throws Exception
     */
    public List getCalendarEvents(String baseUrl, String username, String password, int months) throws Exception {
        List calendarEvents = new ArrayList();
        HttpClient client = new HttpClient();

        if ("true".equals(System.getProperty("lds"))) {
            HostConfiguration conf = client.getHostConfiguration();
            conf.setProxy("extproxy", 80);
        }

        String userContext = getUserContext(client, baseUrl);
        authenticateUser(client, baseUrl, username, password, userContext);

        Calendar cal = Calendar.getInstance();

        // check all this stuff against the calendar javadoc
        for (int i = 0; i < months; i++) {
            int year = cal.get(Calendar.YEAR);
            int month = (cal.get(Calendar.MONTH) + 1);  // Calendar is 0-based for some odd reason
            List events = getEventLinks(client, baseUrl, userContext, month, year);
            calendarEvents.addAll(events);
            cal.add(Calendar.MONTH, 1);
        }

        return calendarEvents;
    }

    private static void processInvalidResponse(int response, HttpMethod request) throws Exception {
        String errorMessage = "An invalid response code was returned: " + response;
        logger.error(errorMessage);
        logger.error("Request body: " + request.getResponseBodyAsString());
        throw new Exception(errorMessage);
    }

    private static String getInputValue(String page, String inputName) {
        int location = 0;
        String tempPage = page.toLowerCase();
        while ((location = (tempPage.indexOf("input", location))) != -1) {
            location++;
            String name = getAttributeValue(page, location, "name");
            if (name == null) continue;
            
            if (name.equals(inputName)) {
                String value = getAttributeValue(page, location, "value");
                return value;
            }
        }
        return null;
    }

    private static String getAttributeValue(String page, int inputStart, String attributeName) {
        String tempPage = page.toLowerCase();

        int value = tempPage.indexOf(attributeName + "=", inputStart);
        if (value == -1) return null;

        int valueQuote = value + (attributeName + "=").length();
        char c = tempPage.charAt(valueQuote);
        if ((c != '\'') && (c != '"')) return null;

        int finalQuote = tempPage.indexOf(String.valueOf(c), valueQuote + 1);
        if (finalQuote == -1) return null;

        return page.substring(valueQuote + 1, finalQuote);
    }

    private String getUserContext(HttpClient client, String baseUrl) throws Exception {
        // get the sign-in web page. This is required to obtain some sort of unique session identifier, called the
        // "User.context"
        GetMethod get = new GetMethod(baseUrl + "/servlet/webacc?User.interface=Frames");
        int response = client.executeMethod(get);
        if (response != 200) processInvalidResponse(response, get);
        String responseBody = get.getResponseBodyAsString();
        String userContext = getInputValue(responseBody, "User.context");
        if (StringUtils.nullOrEmpty(userContext)) {
            throw new Exception("No User.context value found");
        }

        return userContext;
    }

    private void authenticateUser(HttpClient client, String baseUrl, String username, String password,
                                  String userContext) throws Exception {
        PostMethod post = new PostMethod(baseUrl + "/servlet/webacc");
        NameValuePair[] pairs = new NameValuePair[] {
          new NameValuePair("User.id", username),
          new NameValuePair("User.password", password),
          new NameValuePair("User.context", userContext),
          new NameValuePair("User.interface", "frames"),
          new NameValuePair("error", "login"),
          new NameValuePair("merge", "webacc"),
          new NameValuePair("action", "User.Login"),
          new NameValuePair("Url.hasJavaScript", "1")
        };
        post.setRequestBody(pairs);

        int response = client.executeMethod(post);
        if (response != 200) processInvalidResponse(response, post);

        String responseBody = post.getResponseBodyAsString();
    }

    private List getEventLinks(HttpClient client, String baseUrl, String userContext, int month, int year) throws Exception {
        List events = new ArrayList();
        DateFormat gwDateFormat = new SimpleDateFormat("MMMM d, yyyy");
        DateFormat gwTimeFormat = new SimpleDateFormat("h:mm a");

        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1);
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        Date date = calendar.getTime();
        long time = date.getTime();
        GetMethod get = new GetMethod(baseUrl + "/servlet/webacc?User.context=" + userContext + "&action=Calendar.Search&Calendar.durationType=Month&Calendar.queryMonth=" + month + "&Calendar.queryYear=" + year + "&Calendar.startDate=" + time + "&merge=calmonth");

        int response = client.executeMethod(get);
        if (response != 200) processInvalidResponse(response, get);

        Set eventURLs = new HashSet();

        String responseBody = get.getResponseBodyAsString();
        Parser parser = Parser.createParser(responseBody, null);
        Node[] links = parser.extractAllNodesThatAre(LinkTag.class);
        for (int i = 0; i < links.length; i++) {
            LinkTag link = (LinkTag) links[i];
            String target = link.getAttribute("target");
            if ("ItemView".equals(target)) {
                String url = link.getLink();
                get = new GetMethod(baseUrl + url);
                if (eventURLs.contains(url)) continue;
                eventURLs.add(url);
                response = client.executeMethod(get);
                if (response != 200) processInvalidResponse(response, get);

                String mode = null;
                Map values = new HashMap();

                parser = Parser.createParser(get.getResponseBodyAsString(), null);
                Node[] cells = parser.extractAllNodesThatMatch(new TagNameFilter("td")).toNodeArray();
                for (int j = 0; j < cells.length; j++) {
                    Node cell = cells[j];
                    NodeList list = cell.getChildren();
                    if (list == null) continue;
                    Node[] children = list.toNodeArray();
                    for (int k = 0; k < children.length; k++) {
                        Node child = children[k];
                        if (child instanceof Text) {
                            String text = convertTextToString((Text) child);
                            if (text.equals("Subject:")) {
                                mode = text;
                                continue;
                            } else if (text.equals("Date:")) {
                                mode = text;
                                continue;
                            } else if (text.equals("Time:")) {
                                mode = text;
                                continue;
                            } else if (text.equals("To:")) {
                                mode = text;
                                continue;
                            } else if (text.equals("Location:")) {
                                mode = text;
                                continue;
                            }

                            if (text.equals("")) continue;

                            if (mode != null) {
                                values.put(mode, text);
                                mode = null;
                            }
                        }
                    }
                }

                // build the calendar event and add it to the list
                CalendarEvent event = new CalendarEvent();
                event.setLocation((String) values.get("Location:"));
                event.setDescription((String) values.get("Subject:"));

                Date eventDate = null;
                try {
                    String[] gwDate = ((String) values.get("Date:")).split(" - ");
                    eventDate = gwDateFormat.parse(gwDate[1]);
                } catch (Exception e) {
                    logger.error("Couldn't parse Date field", e);
                    logger.error(printFields(values));
                    continue;
                }
                Date startTime = null;
                Date endTime = null;
                try {
                    String[] times = ((String) values.get("Time:")).split(" - ");
                    startTime = gwTimeFormat.parse(times[0]);
                    endTime = gwTimeFormat.parse(times[1]);
                } catch (Exception e) {
                    logger.error("Couldn't parse Time field", e);
                    logger.error(printFields(values));
                    continue;
                }

                Date eventStart = new Date(eventDate.getTime());
                Date eventStop = new Date(eventDate.getTime());
                eventStart.setHours(startTime.getHours());
                eventStart.setMinutes(startTime.getMinutes());
                eventStop.setHours(endTime.getHours());
                eventStop.setMinutes(endTime.getMinutes());

                event.setEventStart(eventStart);
                event.setEventStop(eventStop);

                String to = (String) values.get("To:");
                if (to != null) {
                    String[] attendees = to.split(", ");
                    for (int j = 0; j < attendees.length; j++) {
                        String attendee = attendees[j];
                        event.getAttendees().add(attendee);
                    }
                }

                events.add(event);
            }
        }

        return events;
    }

    private String printFields(Map values) {
        StringBuffer sb = new StringBuffer();
        Iterator it = values.keySet().iterator();
        while (it.hasNext()) {
            String key = (String) it.next();
            sb.append(key);
            sb.append(":");
            sb.append(values.get(key));
            if (it.hasNext()) sb.append(", ");
        }
        return sb.toString();
    }

    private String convertTextToString(Text child) {
        String text = child.getText();
        text = text.replaceAll("\\&nbsp\\;", " ");
        return text.trim();
    }

    public static void main(String[] args) throws Exception {
        BasicConfigurator.configure();

        CalendarScraper scraper = new CalendarScraper();
        List events = scraper.getCalendarEvents("url", "username", "password", 12);
        String vcal = VcalendarExporter.getVcalendar(events);

        File file = new File("/Users/bgalbs/test.ics");
        FileWriter writer = new FileWriter(file);
        writer.write(vcal);
        writer.close();
    }
}
