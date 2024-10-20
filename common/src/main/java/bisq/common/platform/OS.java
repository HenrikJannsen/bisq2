/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.platform;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

@Slf4j
public enum OS {
    LINUX("linux"),
    MAC_OS("macos"),
    WINDOWS("win"),
    ANDROID("android");

    @Getter
    private final String canonicalName;

    OS(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public static OS getOS() {
        String osName = getOsName();
        log.info("osName={}", osName);
        // isAndroid returns Linux as osName, thus we check for it before isLinux and use additional checks inside the method
        if (isAndroid(osName)) {
            return OS.ANDROID;
        } else if (isLinux(osName)) {
            return OS.LINUX;
        } else if (isMacOs(osName)) {
            return OS.MAC_OS;
        } else if (isWindows(osName)) {
            return OS.WINDOWS;
        }
        throw new IllegalStateException("Running on unsupported OS: " + osName);
    }

    public static boolean isLinux() {
        return isLinux(getOsName());
    }

    public static boolean isLinux(String osName) {
        return osName.contains("linux");
    }

    public static boolean isMacOs() {
        return isMacOs(getOsName());
    }

    public static boolean isMacOs(String osName) {
        return osName.contains("mac") || osName.contains("darwin");
    }

    public static boolean isWindows() {
        return isWindows(getOsName());
    }

    public static boolean isWindows(String osName) {
        return osName.contains("win");
    }

    public static boolean isAndroid(String osName) {
        if (isLinux(osName)) {
            return isAndroid();
        } else {
            return false;
        }
    }

    public static boolean isAndroid() {
        //  String property = System.getProperty("java.vendor");
        //        return "The Android Project".equals(property);
        log.info("vendor={}", getJavaVendor());
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static String getOsName() {
        return System.getProperty("os.name").toLowerCase(Locale.US);
    }

    public static String getOsVersion() {
        return System.getProperty("os.version");
    }

    public static String getJavaVendor() {
        return System.getProperty("java.vendor");
    }
}
