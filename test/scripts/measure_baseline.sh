#!/bin/bash
# Measure baseline performance for regression testing

set -e

echo "=== Baseline Measurement Script ==="
echo ""

# Create baselines directory if not exists
mkdir -p baselines

echo "Measuring spaark2 vs SPAARK baseline (20 matches)..."
spaark2_wins=0
spaark2_rounds=0

for i in {1..20}; do
    echo -n "  Match $i/20: "

    result=$(./gradlew run -PteamA=spaark2 -PteamB=SPAARK -Pmaps=DefaultSmall 2>&1)

    if echo "$result" | grep -q "Team A wins"; then
        ((spaark2_wins++))
        echo "WIN"
    else
        echo "LOSS"
    fi

    rounds=$(echo "$result" | grep -oP "Round \K\d+" | tail -1)
    spaark2_rounds=$((spaark2_rounds + rounds))
done

spaark2_winrate=$((spaark2_wins * 5))  # Convert to percentage (wins * 100 / 20)
spaark2_avg_rounds=$((spaark2_rounds / 20))

echo ""
echo "=== RESULTS ==="
echo "spaark2 vs SPAARK (20 matches on DefaultSmall):"
echo "  Win Rate: $spaark2_winrate%"
echo "  Avg Survival: $spaark2_avg_rounds rounds"
echo ""

# Save baselines
echo "$spaark2_winrate" > baselines/spaark2_winrate.txt
echo "$spaark2_avg_rounds" > baselines/spaark2_rounds.txt

# Set realistic targets (spaark2 + margin)
target_winrate=$((spaark2_winrate + 10))
target_rounds=$((spaark2_avg_rounds + 20))

echo "=== TARGETS FOR NEW BOT ==="
echo "  Win Rate: > $target_winrate%"
echo "  Survival: > $target_rounds rounds"
echo ""

echo "Baselines saved to:"
echo "  baselines/spaark2_winrate.txt"
echo "  baselines/spaark2_rounds.txt"
echo ""

# Measure SPAARK benchmarks (manual - from replay observation)
echo "=== SPAARK BENCHMARKS (Manual Measurement Needed) ==="
echo "Watch SPAARK replays and record:"
echo "  1. Paint efficiency (tiles painted / paint used) - estimate ~0.28"
echo "  2. Mode distribution - EXPLORE 65%, BUILD 20%, ATTACK 10%, RETREAT 5%"
echo "  3. Tower build time - 45-65 rounds avg"
echo "  4. Retreat frequency - 2-3 times per soldier per match"
echo ""
echo "Save to: baselines/spaark_benchmarks.txt"
echo ""
echo "DONE"
