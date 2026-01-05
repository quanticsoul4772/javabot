#!/bin/bash
# Helper script to find and display the latest match report

# Find most recent match report
LATEST=$(ls -t match_report_*.txt 2>/dev/null | head -1)

if [ -z "$LATEST" ]; then
    echo "No match reports found"
    echo "Run: ./test/scripts/generate_report.sh <package> <opponent>"
    exit 1
fi

echo "Latest match report: $LATEST"
echo ""
echo "Key logs:"
echo ""

echo "=== STATE SNAPSHOTS ==="
grep "STATE:" "$LATEST" | tail -10
echo ""

echo "=== ECONOMY ==="
grep "ECONOMY:" "$LATEST" | tail -5
echo ""

echo "=== DECISIONS ==="
DECISION_COUNT=$(grep -c "DECISION:" "$LATEST" 2>/dev/null || echo "0")
if [ "$DECISION_COUNT" -gt "0" ]; then
    grep "DECISION:" "$LATEST"
else
    echo "No mode transitions logged"
fi
echo ""

echo "=== CRITICAL EVENTS ==="
grep "CRITICAL:\|DEATH:\|BUILD_ABORT:" "$LATEST" 2>/dev/null || echo "None"
echo ""

echo "Full file: $LATEST"
echo "Lines: $(wc -l < $LATEST)"
