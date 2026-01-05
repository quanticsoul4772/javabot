#!/bin/bash
# Generate comprehensive match report for Claude Code analysis

PACKAGE=$1
OPPONENT=${2:-SPAARK}
MAP=${3:-DefaultSmall}

if [ -z "$PACKAGE" ]; then
    echo "Usage: ./generate_report.sh <package> [opponent] [map]"
    echo "Example: ./generate_report.sh spaark2 SPAARK DefaultSmall"
    exit 1
fi

OUTPUT="match_report_$(date +%s).txt"

echo "Running match: $PACKAGE vs $OPPONENT on $MAP"
echo "Output will be saved to: $OUTPUT"
echo ""

# Run match and capture output
./gradlew run -PteamA=$PACKAGE -PteamB=$OPPONENT -Pmaps=$MAP > "$OUTPUT" 2>&1

echo "Match complete. Generating report..."
echo ""
echo "========================================="
echo "=== MATCH REPORT ==="
echo "========================================="
echo ""

# Extract result
if grep -q "Team A wins" "$OUTPUT"; then
    RESULT="WIN"
else
    RESULT="LOSS"
fi

FINAL_ROUND=$(grep -oP "wins \(round \K\d+" "$OUTPUT" | tail -1)

# Load targets if available
if [ -f baselines/spaark2_rounds.txt ]; then
    TARGET_ROUNDS=$(cat baselines/spaark2_rounds.txt)
    echo "Result: $RESULT at round $FINAL_ROUND (Target: >$TARGET_ROUNDS)"
else
    echo "Result: $RESULT at round $FINAL_ROUND"
fi
echo "Opponent: $OPPONENT on $MAP"
echo ""

# Parse indicators using Java parser
echo "=== BEHAVIOR ANALYSIS ==="
# Compile parser if not already compiled
javac -d test/build test/match/IndicatorParser.java 2>/dev/null || true
java -cp test/build match.IndicatorParser "$OUTPUT" 2>/dev/null || echo "Parser not available or failed"
echo ""

# Extract decision traces
DECISION_COUNT=$(grep -c "DECISION:" "$OUTPUT" 2>/dev/null || echo "0")
if [ "$DECISION_COUNT" -gt "0" ]; then
    echo "=== KEY DECISIONS ($DECISION_COUNT total) ==="
    grep "DECISION:" "$OUTPUT" | head -20
    echo ""
fi

# Extract bytecode profile (if enabled)
PROFILE_COUNT=$(grep -c "PROFILE:" "$OUTPUT" 2>/dev/null || echo "0")
if [ "$PROFILE_COUNT" -gt "0" ]; then
    echo "=== BYTECODE PROFILE ==="
    grep "PROFILE:" "$OUTPUT" | awk -F: '{sum[$4]+=$5; count[$4]++} END {for (section in sum) printf "  %s: %d avg\n", section, sum[section]/count[section]}' | sort
    echo ""
fi

# Extract match statistics
if grep -q "MATCH_SUMMARY:" "$OUTPUT" 2>/dev/null; then
    echo "=== MATCH STATISTICS ==="
    grep "MATCH_SUMMARY" "$OUTPUT" -A 10
    echo ""
fi

# Extract sensed maps
MAP_COUNT=$(grep -c "SENSED_MAP:" "$OUTPUT" 2>/dev/null || echo "0")
if [ "$MAP_COUNT" -gt "0" ]; then
    echo "=== SENSED MAPS ($MAP_COUNT snapshots) ==="
    echo "(Use: grep SENSED_MAP $OUTPUT -A 10 to view)"
    echo ""
fi

# Enemy spawn tracking
echo "=== ENEMY ANALYSIS ==="
ENEMY_SPAWNS=$(grep -c "\[B:.*BUILT" "$OUTPUT" 2>/dev/null || echo "0")
OUR_SPAWNS=$(grep -c "^\\[A:.*\\] SPAWN:" "$OUTPUT" 2>/dev/null || echo "0")
echo "Enemy spawns: $ENEMY_SPAWNS"
echo "Our spawns: $OUR_SPAWNS"

if [ "$OUR_SPAWNS" -gt "0" ] && [ "$ENEMY_SPAWNS" -gt "0" ]; then
    SPAWN_RATIO=$((OUR_SPAWNS * 100 / ENEMY_SPAWNS))
    echo "Spawn ratio: $SPAWN_RATIO% (us vs enemy)"
    if [ "$SPAWN_RATIO" -lt "80" ]; then
        echo "  ⚠️ Spawning significantly slower than enemy"
    fi
fi

# Enemy unit trend
echo ""
echo "Enemy unit trend (sampled every 5th observation):"
grep "ENEMY_COUNT:" "$OUTPUT" | awk -F':' 'NR % 5 == 0 {
    split($6, a, "="); total=a[2];
    split($8, b, "="); soldiers=b[2];
    split($12, c, "="); moppers=c[2];
    split($14, d, "="); towers=d[2];
    print "  Round "$2": total="total" (soldiers="soldiers" moppers="moppers" towers="towers")";
}' | head -10

# Our unit trend
echo ""
echo "Our unit trend (from STATE snapshots):"
grep "STATE:" "$OUTPUT" | awk -F':' 'NR % 5 == 0 {
    split($6, a, "="); paint=a[2];
    split($8, b, "="); mode=b[2];
    split($12, c, "="); allies=c[2];
    split($14, d, "="); enemies=d[2];
    print "  Round "$2": allies="allies" enemies_visible="enemies" (paint="paint" mode="mode")";
}' | head -10

# Force comparison
echo ""
echo "Force Balance Analysis:"
# Count unique soldiers from STATE logs
OUR_SOLDIERS=$(grep "STATE:.*SOLDIER" "$OUTPUT" | awk -F':' '{print $4}' | sort -u | wc -l)
echo "  Our unique soldiers: $OUR_SOLDIERS"
# Max enemy count seen
MAX_ENEMIES=$(grep "ENEMY_COUNT:" "$OUTPUT" | awk -F':' '{split($6, a, "="); print a[2]}' | sort -n | tail -1)
echo "  Max enemies observed at once: $MAX_ENEMIES"

# Economy comparison
echo ""
echo "Economy Comparison:"

# Our economy stats
OUR_MAX_CHIPS=$(grep "ECONOMY:" "$OUTPUT" | awk -F'chips=' '{print $2}' | awk -F':' '{print $1}' | sort -n | tail -1)
OUR_MAX_INCOME=$(grep "ECONOMY:" "$OUTPUT" | awk -F'income=' '{print $2}' | awk -F':' '{print $1}' | sort -n | tail -1)
OUR_START_TOWERS=$(grep "ECONOMY:10:" "$OUTPUT" | awk -F'towers=' '{print $2}' | awk -F':' '{print $1}' | head -1)
OUR_END_TOWERS=$(grep "ECONOMY:" "$OUTPUT" | awk -F'towers=' '{print $2}' | awk -F':' '{print $1}' | tail -1)

echo "  Our Stats:"
echo "    Max chips: $OUR_MAX_CHIPS"
echo "    Max income: $OUR_MAX_INCOME/turn"
echo "    Towers: $OUR_START_TOWERS start → $OUR_END_TOWERS end"

# Enemy tower presence
ENEMY_MAX_TOWERS=$(grep "ENEMY_COUNT:" "$OUTPUT" | awk -F'towers=' '{print $2}' | sort -n | tail -1)
ENEMY_EARLY_TOWERS=$(grep "ENEMY_COUNT:" "$OUTPUT" | head -10 | awk -F'towers=' '{print $2}' | awk '{sum+=$1; count++} END {if(count>0) print int(sum/count); else print 0}')

echo "  Enemy Stats (observed):"
echo "    Max towers seen: $ENEMY_MAX_TOWERS"
echo "    Early game avg towers: $ENEMY_EARLY_TOWERS"

# Detect enemy tower upgrades
if grep -q "LEVEL_TWO.*TOWER" "$OUTPUT"; then
    FIRST_L2=$(grep "LEVEL_TWO.*TOWER" "$OUTPUT" | head -1 | awk -F'@' '{print $2}' | awk -F']' '{print $1}')
    echo "    ⚠️ Enemy upgraded to L2 by round $FIRST_L2"
fi

# Detect our upgrades
OUR_UPGRADES=$(grep -c "UPGRADE:" "$OUTPUT" 2>/dev/null || echo "0")
if [ "$OUR_UPGRADES" -gt "0" ]; then
    echo "    ✓ We upgraded $OUR_UPGRADES time(s)"
else
    echo "    ❌ We NEVER upgraded (max chips $OUR_MAX_CHIPS < 2500 needed)"
fi

echo ""

# Detect issues
echo "=== DETECTED ISSUES ==="
ISSUES_FOUND=0

if grep -qi "bytecode overflow" "$OUTPUT"; then
    OVERFLOW_COUNT=$(grep -ci "bytecode overflow" "$OUTPUT")
    echo "  ❌ Bytecode overflow detected ($OVERFLOW_COUNT instances)"
    ((ISSUES_FOUND++))
fi

if grep -qi "stuck" "$OUTPUT"; then
    echo "  ⚠️ Potential stuck units detected"
    ((ISSUES_FOUND++))
fi

if grep -qi "exception" "$OUTPUT"; then
    echo "  ❌ Exception detected"
    ((ISSUES_FOUND++))
fi

if [ "$ISSUES_FOUND" -eq 0 ]; then
    echo "  ✓ No critical issues detected"
fi
echo ""

# Summary
echo "=== SUMMARY ==="
echo "Full match log saved to: $OUTPUT"
echo ""
echo "To view specific sections:"
echo "  Indicators: grep '\\[A:' $OUTPUT | less"
echo "  Decisions: grep 'DECISION:' $OUTPUT"
echo "  Profile: grep 'PROFILE:' $OUTPUT"
echo "  Stats: grep 'MATCH_SUMMARY' $OUTPUT -A 10"
echo "  Maps: grep 'SENSED_MAP' $OUTPUT -A 10"
echo ""
echo "========================================="
echo "=== END REPORT ==="
echo "========================================="
