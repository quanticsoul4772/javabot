package match;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Parse Battlecode match output and validate bot behavior.
 * Run as standalone Java application.
 */
public class IndicatorParser {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java match.IndicatorParser <match_output.txt>");
            System.exit(1);
        }

        String content = readFile(args[0]);
        Map<String, List<String>> indicators = parseIndicators(content);

        System.out.println("=== INDICATOR ANALYSIS ===");
        System.out.println();

        // Validate soldier modes
        Set<String> modes = validateSoldierModes(indicators);
        System.out.println("Soldier modes seen: " + modes);
        System.out.println("  Expected: {EXPLORE, BUILD_TOWER, ATTACK, RETREAT, BUILD_RESOURCE, EXPAND_RESOURCE, MESSING_UP}");
        System.out.println("  Status: " + (modes.size() >= 4 ? "✓ PASS" : "❌ FAIL (too few modes)"));
        System.out.println();

        // Validate retreat behavior
        List<Integer> retreatPaintValues = validateRetreatTrigger(indicators);
        boolean validRetreats = retreatPaintValues.isEmpty() ||
                                retreatPaintValues.stream().allMatch(p -> p < 150);
        System.out.println("Retreat validation: " + (validRetreats ? "✓ PASS" : "❌ FAIL"));
        if (!retreatPaintValues.isEmpty()) {
            System.out.println("  Retreat paint values: " + retreatPaintValues);
            System.out.println("  All < 150: " + validRetreats);
        } else {
            System.out.println("  No retreats observed");
        }
        System.out.println();

        // Mode distribution
        Map<String, Integer> modeDistribution = calculateModeDistribution(indicators);
        System.out.println("Mode Distribution:");
        int total = modeDistribution.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<String, Integer> entry : modeDistribution.entrySet()) {
            int count = entry.getValue();
            int percent = total > 0 ? (count * 100 / total) : 0;
            System.out.println("  " + entry.getKey() + ": " + count + " turns (" + percent + "%)");
        }
        System.out.println();

        // Exit code for automated testing
        if (modes.size() >= 4 && validRetreats) {
            System.out.println("✓ OVERALL: Behavior validation PASSED");
            System.exit(0);
        } else {
            System.out.println("❌ OVERALL: Behavior validation FAILED");
            System.exit(1);
        }
    }

    static String readFile(String filename) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    static Map<String, List<String>> parseIndicators(String content) {
        // Extract all log lines (STATE, DECISION, SPAWN, etc.)
        // Format: [A: #11577@10] STATE:10:SOLDIER:11577:pos=[11, 12]:paint=148...
        Map<String, List<String>> indicators = new HashMap<>();

        BufferedReader reader = new BufferedReader(new StringReader(content));
        String line;

        try {
            while ((line = reader.readLine()) != null) {
                // Look for team A logs
                if (!line.startsWith("[A:")) continue;

                // Extract the log content (everything after "] ")
                int endBracket = line.indexOf(']');
                if (endBracket == -1) continue;

                String logContent = line.substring(endBracket + 2).trim();

                // Parse log type (STATE, DECISION, SPAWN, etc.)
                if (logContent.startsWith("STATE:") ||
                    logContent.startsWith("DECISION:") ||
                    logContent.startsWith("ENEMIES:") ||
                    logContent.startsWith("SPAWN:") ||
                    logContent.startsWith("ECONOMY:") ||
                    logContent.startsWith("TOWER_ATTACK:") ||
                    logContent.startsWith("BUILD") ||
                    logContent.startsWith("CRITICAL:")) {

                    // Store by log type for analysis
                    String logType = logContent.split(":")[0];
                    indicators.computeIfAbsent(logType, k -> new ArrayList<>()).add(logContent);

                    // Also parse for unit-specific tracking
                    if (logContent.contains("SOLDIER")) {
                        indicators.computeIfAbsent("ALL_SOLDIERS", k -> new ArrayList<>()).add(logContent);
                    }
                }
            }
        } catch (IOException e) {
            // Shouldn't happen with StringReader
        }

        return indicators;
    }

    static Set<String> validateSoldierModes(Map<String, List<String>> indicators) {
        Set<String> modesSeen = new HashSet<>();

        List<String> states = indicators.getOrDefault("STATE", new ArrayList<>());

        for (String state : states) {
            if (!state.contains("SOLDIER")) continue;

            // Extract mode from STATE log: STATE:10:SOLDIER:11577:...:mode=EXPLORE:...
            Pattern modePattern = Pattern.compile(":mode=([^:]+):");
            Matcher matcher = modePattern.matcher(state);
            if (matcher.find()) {
                modesSeen.add(matcher.group(1));
            }
        }

        return modesSeen;
    }

    static List<Integer> validateRetreatTrigger(Map<String, List<String>> indicators) {
        List<Integer> retreatPaintValues = new ArrayList<>();

        List<String> states = indicators.getOrDefault("STATE", new ArrayList<>());

        for (String state : states) {
            // Look for RETREAT mode in STATE logs
            if (state.contains(":mode=RETREAT:")) {
                // Extract paint value
                Pattern paintPattern = Pattern.compile(":paint=(\\d+):");
                Matcher matcher = paintPattern.matcher(state);
                if (matcher.find()) {
                    int paint = Integer.parseInt(matcher.group(1));
                    retreatPaintValues.add(paint);
                }
            }
        }

        return retreatPaintValues;
    }

    static Map<String, Integer> calculateModeDistribution(Map<String, List<String>> indicators) {
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("EXPLORE", 0);
        distribution.put("BUILD_TOWER", 0);
        distribution.put("ATTACK", 0);
        distribution.put("ATTACK_TOWER", 0);
        distribution.put("RETREAT", 0);

        List<String> states = indicators.getOrDefault("STATE", new ArrayList<>());

        for (String state : states) {
            if (!state.contains("SOLDIER")) continue;

            // Extract mode from STATE log
            Pattern modePattern = Pattern.compile(":mode=([^:]+):");
            Matcher matcher = modePattern.matcher(state);
            if (matcher.find()) {
                String mode = matcher.group(1);
                distribution.put(mode, distribution.getOrDefault(mode, 0) + 1);
            }
        }

        return distribution;
    }
}
