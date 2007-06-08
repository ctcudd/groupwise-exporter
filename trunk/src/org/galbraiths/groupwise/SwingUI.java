package org.galbraiths.groupwise;

import com.jformdesigner.runtime.FormCreator;
import com.jformdesigner.runtime.FormLoader;
import com.jformdesigner.model.FormModel;

import javax.swing.*;
import java.util.Properties;
import java.util.List;
import java.util.Date;
import java.io.*;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.ServerSocket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.apache.log4j.Logger;
import simple.http.connect.ConnectionFactory;
import simple.http.connect.Connection;
import simple.http.ProtocolHandler;
import simple.http.Request;
import simple.http.Response;
import ch.randelshofer.quaqua.QuaquaManager;

public class SwingUI {
    private static final Logger logger = Logger.getLogger(SwingUI.class);

    private static String version = "1.0";

    private static ServerSocket serverSocket;
    private static Properties properties;
    private static File appDir;
    private static String calendar = "";
    private static Date lastModified = new Date();
    private static File propFile;
    private static File calFile;
    private static FormCreator creator;
    private static JFrame frame;
    private static boolean stopped = true;
    private static int scrapeCount = 0;
    private static int lastRequestCount = 0;
    private static int lastScrapeDuration = 0;
    private static int hits = 0;
    private static int errorCount = 0;
    private static UIGunk gunk;

    public static void main(String[] args) throws Exception {
        // make sure we're on OS X
        if (!System.getProperty("os.name").equals("Mac OS X")) {
            JOptionPane.showMessageDialog(null, "Unfortunately, despite being written in Java, this application requires Mac OS X.", "Mac Required", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }

        // use the Quaqua look-and-feel
        UIManager.setLookAndFeel(QuaquaManager.getLookAndFeelClassName());

        // construct the main frame
        frame = new JFrame("Groupwise Scraper");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // load the runtime form using JFormDesigner's API
        FormModel model = FormLoader.load("main.jfd");
        creator = new FormCreator(model);
        frame.getContentPane().add(creator.createPanel());

        // set the version number
        creator.getLabel("titleLabel").setText(creator.getLabel("titleLabel").getText() + version);

        // add listeners
        creator.getButton("quit").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                quit();
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        creator.getRadioButton("radioToday").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                creator.getTextField("startingDate").setEnabled(false);
            }
        });
        creator.getRadioButton("radioCustom").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                creator.getTextField("startingDate").setEnabled(true);
            }
        });
        creator.getButton("start").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                start();
                creator.getButton("start").setEnabled(false);
                creator.getButton("stop").setEnabled(true);
            }
        });
        creator.getButton("stop").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                stop();
                creator.getButton("start").setEnabled(true);
                creator.getButton("stop").setEnabled(false);
            }
        });
        creator.getButton("showErrorLog").addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JScrollPane ta = creator.getScrollPane("errorScroll");
                if (ta.isVisible()) {
                    ta.setVisible(false);
                    creator.getButton("showErrorLog").setText("Show Error Log");
                } else {
                    ta.setVisible(true);
                    creator.getButton("showErrorLog").setText("Hide Error Log");
                }
            }
        });

        // get app directory
        String appDirLocation = System.getProperty("user.home") + File.separator + "Library" + File.separator + "GroupWise Exporter";
        appDir = new File(appDirLocation);

        // load preferences
        properties = new Properties();
        propFile = new File(appDir, "settings.properties");
        if (propFile.exists()) {
            FileInputStream in = new FileInputStream(propFile);
            properties.load(in);
            in.close();
        } else {
            // set some default values
            properties.setProperty("months", "12");
            properties.setProperty("minutes", "10");
            properties.setProperty("port", "8123");
            properties.setProperty("today", "true");
        }

        // populate UI with values from preferences
        creator.getTextField("url").setText(properties.getProperty("url"));
        creator.getTextField("proxy").setText(properties.getProperty("proxy"));
        creator.getTextField("username").setText(properties.getProperty("username"));
        creator.getTextField("password").setText(properties.getProperty("password"));
        creator.getTextField("port").setText(properties.getProperty("port"));
        creator.getTextField("startingDate").setText(properties.getProperty("startingDate"));
        creator.getSpinner("months").setValue(Integer.parseInt(properties.getProperty("months")));
        creator.getSpinner("minutes").setValue(Integer.parseInt(properties.getProperty("minutes")));
        String today = properties.getProperty("today");
        if ("false".equals(today)) {
            creator.getRadioButton("radioCustom").setSelected(true);
        } else {
            creator.getRadioButton("radioToday").setSelected(true);
        }
        String proxyOn = properties.getProperty("proxyOn");
        if ("true".equals(proxyOn)) {
            creator.getCheckBox("checkProxy").setSelected(true);
        } else {
            creator.getCheckBox("checkProxy").setSelected(false);
        }

        // load initial calendar, if any
        calFile = new File(appDir, "groupwise.ics");
        if (calFile.exists()) {
            FileInputStream in = new FileInputStream(calFile);
            byte[] bytes = new byte[(int) calFile.length()];
            in.read(bytes);
            in.close();
            calendar = new String(bytes);
        }

        // misc UI tweaks
        creator.getButton("showErrorLog").putClientProperty("Quaqua.Button.style", "square");
        creator.getScrollPane("errorScroll").setVisible(false);

        // display the frame
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        setCalendarStatistics(0);
        setErrors();
        setEngineStatistics();
        setScraperStatus("Idle");
        setEngineStatus("Idle");
    }

    private static void quit() {
        try {
            properties.setProperty("url", creator.getTextField("url").getText());
            properties.setProperty("proxy", creator.getTextField("proxy").getText());
            properties.setProperty("username", creator.getTextField("username").getText());
            properties.setProperty("password", creator.getTextField("password").getText());
            properties.setProperty("port", creator.getTextField("port").getText());
            properties.setProperty("startingDate", creator.getTextField("startingDate").getText());
            properties.setProperty("months", String.valueOf(creator.getSpinner("months").getValue()));
            properties.setProperty("minutes", String.valueOf(creator.getSpinner("minutes").getValue()));
            if (creator.getRadioButton("radioToday").isSelected()) {
                properties.setProperty("today", "true");
            } else {
                properties.setProperty("today", "false");
            }
            if (creator.getCheckBox("checkProxy").isSelected()) {
                properties.setProperty("proxyOn", "true");
            } else {
                properties.setProperty("proxyOn", "false");
            }

            propFile.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(propFile);
            properties.store(out, null);
            out.close();

            out = new FileOutputStream(calFile);
            out.write(calendar.getBytes());
            out.close();

            frame.dispose();
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void start() {
        disable("checkProxy", "proxy", "radioToday", "radioCustom", "start", "url", "username", "password", "port", "months", "minutes", "startingDate");
        enable("stop");

        Integer minutes = (Integer) creator.getSpinner("minutes").getValue();
        final long ms = minutes * 60 * 1000;

        final String url = creator.getTextField("url").getText();
        final String username = creator.getTextField("username").getText();
        final String password = creator.getTextField("password").getText();
        String proxy = null;
        if (creator.getCheckBox("checkProxy").isSelected()) {
            proxy = creator.getTextField("proxy").getText();
        }
        final int port = Integer.parseInt(creator.getTextField("port").getText());
        final Integer months = (Integer) creator.getSpinner("months").getValue();

        stopped = false;
        final String proxy1 = proxy;
        new Thread("Calendar refresh thread") {
            public void run() {
                long lastRun = 0;
                while (!stopped) {
                    long diff = System.currentTimeMillis() - lastRun;
                    if (diff < ms) {
                        setScraperStatus("Waiting for next scrape");
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {}
                        continue;
                    }

                    setScraperStatus("Scraping GroupWise");

                    // do something
                    try {
                        CalendarScraperMinimal minimal = new CalendarScraperMinimal();
                        gunk = new UIGunk();
                        long startScrape = System.currentTimeMillis();
                        List events = minimal.getCalendarEvents(url, username, password, months, proxy1, gunk);
                        long stopScrape = System.currentTimeMillis();

                        if (!stopped) {
                            scrapeCount++;
                            lastScrapeDuration = (int) (stopScrape - startScrape);
                            lastRequestCount = gunk.getCounter();
                            setCalendarStatistics(events.size());

                            String cal = VcalendarExporter.getVcalendar(events);
                            if (!calendar.equals(cal)) {
                                calendar = cal;
                                lastModified = new Date();
                            }
                        }
                    } catch (Exception e) {
                        error("Couldn't get calendar", e);
                        lastRun = System.currentTimeMillis();
                        continue;
                    }

                    lastRun = System.currentTimeMillis();
                }

                setScraperStatus("Idle");
                enable("checkProxy", "proxy", "radioToday", "radioCustom", "start", "url", "username", "password", "port", "months", "minutes");
                if (creator.getRadioButton("radioCustom").isSelected()) enable("startingDate");

            }
        }.start();

        try {
            setEngineStatus("Accepting Connections");
            Connection connect = ConnectionFactory.getConnection(new ProtocolHandler() {
                public void handle(Request request, Response response) {
                    synchronized (SwingUI.class) {
                        hits++;
                        setEngineStatistics();
                    }

                    try {
                        byte[] bytes = calendar.getBytes("UTF8");

                        DateFormat df = new SimpleDateFormat("EE, dd MMM yyyy HH:mm:ss z");
                        response.set("Server", "GroupWise Exporter v" + version);
                        response.set("Date", df.format(new Date()));
                        response.set("Content-Type", "text/plain;charset=us-ascii");
                        response.set("Last-Modified", df.format(lastModified));

                        response.setContentLength(bytes.length);

                        OutputStream out = response.getOutputStream();
                        out.write(bytes);
                        out.close();
                        response.commit();
                    } catch (IOException e) {
                        error("Couldn't write response", e);
                    }
                }
            });

            synchronized (SwingUI.class) {
                serverSocket = new ServerSocket(port);
            }

            connect.connect(serverSocket);
        } catch (IOException e) {
            setEngineStatus("Idle");
            error("Couldn't start HTTP engine", e);
        }
    }

    private static void stop() {
        stopped = true;
        if (gunk != null) gunk.setStopped(true);
        try {
            serverSocket.close();
            setEngineStatus("Idle");
        } catch (IOException e) {
            error("Couldn't close server socket", e);
            setEngineStatus("Unknown");
        }
        disable("stop");
    }

    private static void setScraperStatus(String status) {
        setLabel("scraperStatus", status);
    }

    private static void setEngineStatus(String status) {
        setLabel("engineStatus", status);
    }

    private static void setCalendarStatistics(int eventCount) {
        setLabel("statScrapeCount", "GroupWise scraped " + scrapeCount + " time(s)");
        if (scrapeCount == 0) {
            setLabel("statScrape", "");
            setLabel("statEvents", "");
        } else {
            String time = "";
            if (lastScrapeDuration > 1000) {
                time = (lastScrapeDuration / 1000) + " secs";
            } else {
                time = lastScrapeDuration + " ms";
            }
            setLabel("statScrape", lastRequestCount + " requests, " + time + " for last scrape");
            setLabel("statEvents", eventCount + " events in calendar");
        }
    }

    private static synchronized void setEngineStatistics() {
        setLabel("statRequests", hits + " request(s) made to HTTP engine");
    }

    private static void disable(String... names) {
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            JComponent c = (JComponent) creator.getComponent(name);
            c.setEnabled(false);
        }
    }

    private static void enable(String... names) {
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            JComponent c = (JComponent) creator.getComponent(name);
            c.setEnabled(true);
        }
    }

    private static void setLabel(String label, String text) {
        creator.getLabel(label).setText(text);
    }

    private synchronized static void error(String description, Throwable t) {
        logger.error(description, t);

        errorCount++;
        JTextArea textArea = creator.getTextArea("errorLog");
        String text = textArea.getText();
        text += description + "\n";
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        text += sw.getBuffer().toString() + "\n";
        pw.close();
        try {
            sw.close();
        } catch (IOException e) {
            logger.error("Couldn't close StringWriter", e);
        }
        textArea.setText(text);
        setErrors();
    }

    private static void setErrors() {
        setLabel("statErrors", errorCount + " error(s) occurred");
    }

    public static class UIGunk {
        private int counter;
        private boolean stopped = false;

        public UIGunk() {
            this.counter = 0;
        }

        public UIGunk(int counter) {
            this.counter = counter;
        }

        public int getCounter() {
            return counter;
        }

        public boolean isStopped() {
            return stopped;
        }

        public void setStopped(boolean stopped) {
            this.stopped = stopped;
        }

        public synchronized void increment() {
            counter++;
        }
    }
}