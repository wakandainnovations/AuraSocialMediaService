package com.lit.fire.flame;

import java.io.InputStream;
import java.util.Properties;

public class AppProperties {

    private static final Properties properties = new Properties();

    static {
        try (InputStream input = AppProperties.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                System.out.println("Sorry, unable to find application.properties");
            }
            properties.load(input);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Whether a platform ("x", "instagram", "reddit", "youtube") is collected from, per the
     * comma-separated scan.platforms property. Unset means all four.
     */
    public static boolean isPlatformEnabled(String platform) {
        String value = properties.getProperty("scan.platforms");
        if (value == null || value.isBlank()) {
            return true;
        }
        for (String p : value.split(",")) {
            if (p.trim().equalsIgnoreCase(platform)) {
                return true;
            }
        }
        return false;
    }

    public static int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // Ignore and return default
            }
        }
        return defaultValue;
    }
}