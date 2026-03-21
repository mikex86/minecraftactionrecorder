package me.michael.kei.actionrecorder;

import net.minecraft.SystemReport;
import oshi.SystemInfo;
import oshi.hardware.Baseboard;
import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.Firmware;
import oshi.hardware.GraphicsCard;
import oshi.hardware.PhysicalMemory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class HardwareIdentity {

    private static final Set<String> REPORT_STABLE_PREFIXES = Set.of(
            "operating system",
            "processor vendor",
            "processor name",
            "identifier",
            "microarchitecture",
            "memory slot #",
            "graphics card #"
    );

    private HardwareIdentity() {
    }

    public static String computeHardwareDerivedId() {
        Set<String> material = new TreeSet<>();
        material.add("schema=v2");

        collectFromMinecraftSystemReport(material);
        collectFromOshiHardware(material);

        if (material.size() <= 1) {
            material.add("fallback.os.name=" + System.getProperty("os.name", "unknown"));
            material.add("fallback.os.arch=" + System.getProperty("os.arch", "unknown"));
            material.add("fallback.user=" + System.getProperty("user.name", "unknown"));
        }

        List<String> sorted = new ArrayList<>(material);
        Collections.sort(sorted);
        String joined = String.join("|", sorted);
        return sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static void collectFromMinecraftSystemReport(Set<String> material) {
        try {
            String report = new SystemReport().toLineSeparatedString();
            for (String rawLine : report.split("\\R")) {
                String line = rawLine.strip();
                if (line.startsWith("-")) {
                    line = line.substring(1).strip();
                }
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (value.isEmpty()) {
                    continue;
                }

                String normalized = key.toLowerCase(Locale.ROOT);
                for (String prefix : REPORT_STABLE_PREFIXES) {
                    if (normalized.startsWith(prefix)) {
                        material.add("report." + normalized + "=" + value);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectFromOshiHardware(Set<String> material) {
        try {
            SystemInfo systemInfo = new SystemInfo();
            var hardware = systemInfo.getHardware();
            if (hardware == null) {
                return;
            }

            CentralProcessor processor = hardware.getProcessor();
            if (processor != null) {
                CentralProcessor.ProcessorIdentifier id = processor.getProcessorIdentifier();
                if (id != null) {
                    putIfValue(material, "cpu.vendor", id.getVendor());
                    putIfValue(material, "cpu.name", id.getName());
                    putIfValue(material, "cpu.identifier", id.getIdentifier());
                    putIfValue(material, "cpu.family", id.getFamily());
                    putIfValue(material, "cpu.model", id.getModel());
                    putIfValue(material, "cpu.stepping", id.getStepping());
                    putIfValue(material, "cpu.processorId", id.getProcessorID());
                    putIfValue(material, "cpu.microarch", id.getMicroarchitecture());
                }
            }

            ComputerSystem computerSystem = hardware.getComputerSystem();
            if (computerSystem != null) {
                putIfValue(material, "system.manufacturer", computerSystem.getManufacturer());
                putIfValue(material, "system.model", computerSystem.getModel());
                putIfValue(material, "system.serial", computerSystem.getSerialNumber());
                putIfValue(material, "system.uuid", computerSystem.getHardwareUUID());

                Baseboard baseboard = computerSystem.getBaseboard();
                if (baseboard != null) {
                    putIfValue(material, "baseboard.manufacturer", baseboard.getManufacturer());
                    putIfValue(material, "baseboard.model", baseboard.getModel());
                    putIfValue(material, "baseboard.version", baseboard.getVersion());
                    putIfValue(material, "baseboard.serial", baseboard.getSerialNumber());
                }

                Firmware firmware = computerSystem.getFirmware();
                if (firmware != null) {
                    putIfValue(material, "firmware.manufacturer", firmware.getManufacturer());
                    putIfValue(material, "firmware.name", firmware.getName());
                    putIfValue(material, "firmware.description", firmware.getDescription());
                    putIfValue(material, "firmware.version", firmware.getVersion());
                    putIfValue(material, "firmware.releaseDate", firmware.getReleaseDate());
                }
            }

            List<GraphicsCard> cards = hardware.getGraphicsCards();
            for (int i = 0; i < cards.size(); i++) {
                GraphicsCard card = cards.get(i);
                putIfValue(material, "gpu." + i + ".name", card.getName());
                putIfValue(material, "gpu." + i + ".vendor", card.getVendor());
                putIfValue(material, "gpu." + i + ".deviceId", card.getDeviceId());
                putIfValue(material, "gpu." + i + ".versionInfo", card.getVersionInfo());
            }

            List<PhysicalMemory> dimms = hardware.getMemory() != null
                    ? hardware.getMemory().getPhysicalMemory()
                    : List.of();
            for (int i = 0; i < dimms.size(); i++) {
                PhysicalMemory dimm = dimms.get(i);
                putIfValue(material, "ram." + i + ".manufacturer", dimm.getManufacturer());
                putIfValue(material, "ram." + i + ".part", callOptionalStringMethod(dimm, "getPartNumber"));
                putIfValue(material, "ram." + i + ".serial", callOptionalStringMethod(dimm, "getSerialNumber"));
                putIfValue(material, "ram." + i + ".type", dimm.getMemoryType());
                putIfValue(material, "ram." + i + ".capacity", Long.toString(dimm.getCapacity()));
                putIfValue(material, "ram." + i + ".clock", Long.toString(dimm.getClockSpeed()));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putIfValue(Set<String> material, String key, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || "unknown".equalsIgnoreCase(normalized)) {
            return;
        }
        material.add(key + "=" + normalized);
    }

    private static String callOptionalStringMethod(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
