/** Characterisation of the agreed website rule; not a new checkout of the web repository. */
export function referencePositions(scores, cooperative = false, team = null) {
  if (cooperative && team !== null) return scores.map(() => team ? 1 : 2);
  const sorted = scores.map((score, index) => ({ ...score, index })).sort((a, b) => {
    if (a.winner && b.winner) return 0;
    if (a.winner) return -1;
    if (b.winner) return 1;
    if (a.score !== null && b.score !== null) return Number(b.score) - Number(a.score);
    return 0;
  });
  const positions = [];
  sorted.forEach((score, rank) => { positions[score.index] = score.winner ? 1 : rank + 1; });
  return positions;
}

export function rankingVectors() {
  const vectors = [];
  const values = [null, '0', '5'];
  for (let n = 1; n <= 6; n++) {
    for (let variant = 0; variant < 3 ** n; variant++) {
      let digits = variant;
      const scores = Array.from({ length: n }, () => { const score = values[digits % 3]; digits = Math.floor(digits / 3); return { score, winner: false }; });
      vectors.push({ scores, cooperative: false, team: null });
    }
  }
  let seed = 20261014;
  const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed; };
  const decimals = [...values, '-1.25', '9007199254740993.125', '9007199254740992.125', '1' + '0'.repeat(308)];
  for (let sample = 0; sample < 500; sample++) {
    const n = 1 + random() % 63;
    const cooperative = sample % 7 === 0;
    const team = cooperative ? (sample % 3 === 0 ? null : sample % 2 === 0) : null;
    const scores = Array.from({ length: n }, () => ({ score: decimals[random() % decimals.length], winner: team === null ? random() % 4 === 0 : team }));
    vectors.push({ scores, cooperative, team });
  }
  return vectors.map(vector => ({ ...vector, expected: referencePositions(vector.scores, vector.cooperative, vector.team) }));
}
