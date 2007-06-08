package org.galbraiths.utils;

import org.apache.log4j.Logger;

import java.lang.reflect.Method;

public class StringUtils {
    private static final Logger logger = Logger.getLogger(StringUtils.class);

    public static boolean nullOrEmpty(String s) {
        return (s == null) || "".equals(s);
    }

    public static boolean notNullOrEmpty(String s) {
        return !nullOrEmpty(s);
    }

    public static void trimProperties(String[] properties, Object o) {
        for (int i = 0; i < properties.length; i++) {
            String property = properties[i];
            trimProperty(property, o);
        }
    }

    public static void trimProperty(String property, Object o) {
        try {
            String getter = ReflectionUtils.getGetterMethod(property);
            Method getterMethod = o.getClass().getMethod(getter, null);
            String value = (String) getterMethod.invoke(o, null);
            value = value.trim();
            String setter = ReflectionUtils.getSetterMethod(property);
            Method setterMethod = o.getClass().getMethod(setter, new Class[] { String.class });
            setterMethod.invoke(o, new Object[] { value });
        } catch (Exception e) {
            logger.error("Couldn't trim property \"" + property + "\" on instance of \"" +
                    o.getClass().getName() + "\"", e);
        }
    }
}
