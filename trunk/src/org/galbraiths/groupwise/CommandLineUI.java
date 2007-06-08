package org.galbraiths.groupwise;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.List;
import java.util.Date;

public class CommandLineUI {
    public static void main(String[] args) throws Exception {
        try {
            String appDirLocation = System.getProperty("user.home") + File.separator + ".groupwiseexporter";
            System.setProperty("log4j.configuration", new File(appDirLocation + "/log4j.properties").toURL().toString());

            File appDir = new File(appDirLocation);

            // load preferences
            Properties properties = new Properties();
            File propFile = new File(appDir, "settings.properties");
            if (propFile.exists()) {
                FileInputStream in = new FileInputStream(propFile);
                properties.load(in);
                in.close();
            } else {
                System.err.println("The configuration file was not found. You should have a file named settings.properties in HOME_DIR/.groupwiseexporter");
                return;
            }

            CalendarScraperMinimal minimal = new CalendarScraperMinimal();
            List events = minimal.getCalendarEvents(properties.getProperty("url"),
                    properties.getProperty("username"),
                    properties.getProperty("password"),
                    Integer.parseInt(properties.getProperty("months")),
                    properties.getProperty("proxy"),
                    null);

            String calendar = VcalendarExporter.getVcalendar(events);
            System.out.println(calendar);
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}
