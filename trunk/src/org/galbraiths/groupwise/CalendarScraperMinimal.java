package org.galbraiths.groupwise;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.Log;
import org.galbraiths.utils.StringUtils;
import org.htmlparser.Parser;
import org.htmlparser.Node;
import org.htmlparser.Text;
import org.htmlparser.util.NodeList;
import org.htmlparser.filters.TagNameFilter;
import org.htmlparser.tags.LinkTag;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.text.SimpleDateFormat;
import java.text.DateFormat;

public class CalendarScraperMinimal {
    // I despise commons-logging but am using it as commons HTTP client uses it
    private static Log logger = LogFactory.getLog(CalendarScraperMinimal.class);

    /**
     * Converts the GroupWise 7 (and earlier?) *simple* web interface into a series of {@link org.galbraiths.groupwise.CalendarEvent}s.
     *
     * @param baseUrl   the base of all the requests, such as <code>https://web.mydomain.com/</code>. The trailing slash
     *                  is required.
     * @param username
     * @param password
     * @return
     * @throws Exception
     */
    public List getCalendarEvents(String baseUrl, String username, String password, int months, String proxy, SwingUI.UIGunk gunk) throws Exception {
        if (gunk == null) {
            gunk = new SwingUI.UIGunk();
        }

        // trim the last slash if present
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        List calendarEvents = new ArrayList();
        HttpClient client = new HttpClient();
        client.getParams().setParameter("http.protocol.single-cookie-header", true);

        if (StringUtils.notNullOrEmpty(proxy)) {
            HostConfiguration conf = client.getHostConfiguration();
            conf.setProxy(proxy, 80);
        }

        String userContext = getUserContext(client, baseUrl);

        gunk.increment();
        if (gunk.isStopped()) return null;

        authenticateUser(client, baseUrl, username, password, userContext);
        gunk.increment();
        if (gunk.isStopped()) return null;

        Calendar cal = Calendar.getInstance();

        // check all this stuff against the calendar javadoc
        for (int i = 0; i < months; i++) {
            int year = cal.get(Calendar.YEAR);
            int month = (cal.get(Calendar.MONTH) + 1);  // Calendar is 0-based for some odd reason
            List events = getEventLinks(client, baseUrl, userContext, month, year, gunk);
            if (gunk.isStopped()) return null;
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
        GetMethod get = new GetMethod(baseUrl + "/gw/webacc?User.interface=Simple");
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
        PostMethod post = new PostMethod(baseUrl + "/gw/webacc");
        NameValuePair[] pairs = new NameValuePair[] {
                new NameValuePair("User.id", username),
                new NameValuePair("User.password", password),
                new NameValuePair("User.interface", "simple"),
                new NameValuePair("User.context", userContext),
                new NameValuePair("error", "login"),
                new NameValuePair("merge", "main"),
                new NameValuePair("action", "User.Login"),
                new NameValuePair("Url.displayDraftItems", "1")
        };
        post.setRequestBody(pairs);

        int response = client.executeMethod(post);
        if (response != 200) processInvalidResponse(response, post);

        String responseBody = post.getResponseBodyAsString();
    }

    private List getEventLinks(HttpClient client, String baseUrl, String userContext, int month, int year, SwingUI.UIGunk counter) throws Exception {
        List events = new ArrayList();
        DateFormat gwDateFormat = new SimpleDateFormat("MMMM d, yyyy");
        DateFormat gwTimeFormat = new SimpleDateFormat("h:mm a");

        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month - 1, 1);
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        Date date = calendar.getTime();
        long time = date.getTime();
        GetMethod get = new GetMethod(baseUrl + "/gw/webacc?User.context=" + userContext + "&action=Calendar.Search&Calendar.startDate=" + time + "&Calendar.durationType=Month&merge=calendar");

        int response = client.executeMethod(get);
        counter.increment();
        if (response != 200) processInvalidResponse(response, get);

        Set eventURLs = new HashSet();

        String responseBody = get.getResponseBodyAsString();
        Parser parser = Parser.createParser(responseBody, null);
        Node[] links = parser.extractAllNodesThatAre(LinkTag.class);
        for (int i = 0; i < links.length; i++) {
            LinkTag link = (LinkTag) links[i];
            String href = link.getAttribute("href");
            if (href.indexOf("Item.Read") != -1) {
                String url = link.getLink();
                if (eventURLs.contains(url)) continue;
                eventURLs.add(url);
                get = new GetMethod(baseUrl + url);
                response = client.executeMethod(get);
                if (counter.isStopped()) return null;
                counter.increment();
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

//                parser = Parser.createParser(responseBody, null);
//                Node[] layers = parser.extractAllNodesThatMatch(new TagNameFilter("layer")).toNodeArray();
//                if (layers.length > 0) {
//                    Node table = layers[0].getChildren().toNodeArray()[0];
//                    Node tr = table.getChildren().toNodeArray()[0];
//                    Node cell = tr.getChildren().toNodeArray()[0];
//                    String description = cell.getText();
//                    description = description.replaceAll("<br>", "\n");
//                }


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
        CalendarScraperMinimal scraper = new CalendarScraperMinimal();
        List events = scraper.getCalendarEvents("https://webmail-wh.ldschurch.org", "galbraithbl", "password01", 12, null, new SwingUI.UIGunk());
        String vcal = VcalendarExporter.getVcalendar(events);

        File file = new File("/Users/bgalbs/test.ics");
        FileWriter writer = new FileWriter(file);
        writer.write(vcal);
        writer.close();
    }
}
