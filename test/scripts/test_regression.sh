#!/bin/bash
# Automated regression testing with statistical validation

PACKAGE=$1
OPPONENT=${2:-spaark2}
NUM_MATCHES=${3:-20}

if [ -z "$PACKAGE" ]; then
    echo "Usage: ./test_regression.sh <package> [opponent] [num_matches]"
    echo "Example: ./test_regression.sh mybot spaark2 20"
    exit 1
fi

echo "=== REGRESSION TEST ==="
echo "Package: $PACKAGE"
echo "Opponent: $OPPONENT"
echo "Matches: $NUM_MATCHES"
echo ""

# Load baselines if available
if [ -f baselines/spaark2_rounds.txt ] && [ -f baselines/spaark2_winrate.txt ]; then
    BASELINE_ROUNDS=$(cat baselines/spaark2_rounds.txt)
    BASELINE_WINRATE=$(cat baselines/spaark2_winrate.txt)
    TARGET_ROUNDS=$((BASELINE_ROUNDS + 20))
    TARGET_WINRATE=$((BASELINE_WINRATE + 5))

    echo "Baselines loaded:"
    echo "  spaark2 win rate: $BASELINE_WINRATE%"
    echo "  spaark2 survival: $BASELINE_ROUNDS rounds"
    echo ""
    echo "Targets:"
    echo "  Win rate: > $TARGET_WINRATE%"
    echo "  Survival: > $TARGET_ROUNDS rounds"
else
    echo "⚠️  No baselines found. Run measure_baseline.sh first."
    echo "   Continuing without baseline comparison..."
    TARGET_ROUNDS=0
    TARGET_WINRATE=0
fi

echo ""
echo "Running $NUM_MATCHES matches..."
echo ""

# Run matches
wins=0
totalRounds=0
results=()

for i in $(seq 1 $NUM_MATCHES); do
    echo -n "Match $i/$NUM_MATCHES: "

    result=$(./gradlew run -PteamA=$PACKAGE -PteamB=$OPPONENT -Pmaps=DefaultSmall 2>&1)

    if echo "$result" | grep -q "Team A wins"; then
        ((wins++))
        echo "WIN"
    else
        echo "LOSS"
    fi

    # Extract final round
    rounds=$(echo "$result" | grep -oP "Round \K\d+" | tail -1)
    totalRounds=$((totalRounds + rounds))
    results+=("$rounds")
done

# Calculate statistics
avgRounds=$((totalRounds / NUM_MATCHES))
winRate=$((wins * 100 / NUM_MATCHES))

# Calculate min/max
minRounds=$(printf '%s\n' "${results[@]}" | sort -n | head -1)
maxRounds=$(printf '%s\n' "${results[@]}" | sort -n | tail -1)

# Calculate standard deviation (approximate)
sum=0
for r in "${results[@]}"; do
    diff=$((r - avgRounds))
    diff_sq=$((diff * diff))
    sum=$((sum + diff_sq))
done
variance=$((sum / NUM_MATCHES))

echo ""
echo "=== RESULTS ===="
echo "Matches: $NUM_MATCHES"
echo "Win Rate: $winRate% (won $wins/$NUM_MATCHES)"
echo "Survival: $avgRounds rounds (min: $minRounds, max: $maxRounds, variance: $variance)"
echo ""

# Regression check
REGRESSION=0

if [ "$TARGET_WINRATE" -gt 0 ]; then
    if [ $winRate -lt $TARGET_WINRATE ]; then
        echo "❌ REGRESSION: Win rate $winRate% < target $TARGET_WINRATE%"
        REGRESSION=1
    else
        echo "✓ Win rate $winRate% >= target $TARGET_WINRATE%"
    fi
fi

if [ "$TARGET_ROUNDS" -gt 0 ]; then
    if [ $avgRounds -lt $TARGET_ROUNDS ]; then
        echo "❌ REGRESSION: Survival $avgRounds < target $TARGET_ROUNDS"
        REGRESSION=1
    else
        echo "✓ Survival $avgRounds >= target $TARGET_ROUNDS"
    fi
fi

echo ""

if [ $REGRESSION -eq 1 ]; then
    echo "========================================="
    echo "❌ REGRESSION DETECTED - DO NOT COMMIT"
    echo "========================================="
    exit 1
else
    echo "========================================="
    echo "✅ ALL CHECKS PASSED"
    echo "========================================="
    exit 0
fi
