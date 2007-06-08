package org.galbraiths.utils;

import java.lang.reflect.Method;

public class ReflectionUtils {
    public static String getSetterMethod(String property) {
        return getEtterMethod(property, "set");
    }

    public static String getGetterMethod(String property) {
        return getEtterMethod(property, "get");
    }

    private static String getEtterMethod(String property, String type) {
        return type + property.substring(0, 1).toUpperCase() + property.substring(1);
    }

    public static boolean hasOverloadedMethod(String methodName, Class[] args, Class baseClass, Class subClass) {
        Class currentClass = subClass;
        while (!currentClass.equals(baseClass)) {
            try {
                Method method = currentClass.getDeclaredMethod(methodName, args);
            } catch (NoSuchMethodException e) {
                currentClass = currentClass.getSuperclass();
            }
            return true;
        }
        return false;
    }

    public static String getActionName(Class clazz) {
        String name = clazz.getName();
        int lastPeriod = name.lastIndexOf('.');
        String actionName = name.substring(lastPeriod + 1);
        String withoutSuffix = actionName.substring(0, actionName.length() - "Action".length());

        StringBuffer newName = new StringBuffer();
        for (int i = 0; i < withoutSuffix.length(); i++) {
            char c = withoutSuffix.charAt(i);
            if ((Character.isUpperCase(c)) && (i != 0)) newName.append(" ");
            newName.append(c);
        }

        return newName.toString();
    }
}
