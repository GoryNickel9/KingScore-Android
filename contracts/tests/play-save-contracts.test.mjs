import test from 'node:test';
import assert from 'node:assert/strict';
import { contract, validatePlaySave, jsonbText } from './play-save-validation.mjs';

const id = value => `00000000-0000-0000-0000-${String(value).padStart(12, '0')}`;
const score = () => ({ id: null, player_id: id(2), score: '9007199254740993.125', winner: true, color: 'red', role: 'builder' });
const payload = () => ({ game_id: id(1), played_at: '2026-10-14', duration_minutes: null, source: 'offline', notes: null, is_cooperative: false, team_won: null, scores: [score()] });
const create = () => ({ p_request_id: id(3), p_payload: payload() });
const update = () => ({ ...create(), p_play_id: id(4), p_expected_revision: 'v1:opaque' });
const checkCreate = args => validatePlaySave('create_play_v1', args);

test('all five proposed commands have valid concrete examples', () => {
  checkCreate(create());
  validatePlaySave('get_play_editor_v1', { p_play_id: id(4) });
  validatePlaySave('update_play_v1', update());
  validatePlaySave('delete_play_v1', { p_request_id: id(3), p_play_id: id(4), p_expected_revision: 'v1:opaque' });
  validatePlaySave('import_bga_play_v1', { ...create(), p_metadata: { location: null, bga_table_id: '12345' }, p_positions: [7] });
});
for (const value of [null, '0', '-1.25', '9007199254740993.125', '0.' + '0'.repeat(306) + '1']) {
  test(`score preserves ${value}`, () => {
    const command = create(); command.p_payload.scores[0].score = value;
    assert.equal(checkCreate(command).p_payload.scores[0].score, value);
  });
}
for (const value of [0, 1.25, '', '1e3', 'NaN', 'Infinity', '01', '1,25', '+' + '1', '1' + '0'.repeat(400), '0.' + '0'.repeat(400) + '1']) {
  test(`reject invalid or unrepresentable score ${String(value).slice(0, 18)}`, () => {
    const command = create(); command.p_payload.scores[0].score = value;
    assert.throws(() => checkCreate(command), TypeError);
  });
}
test('invalid dates and out-of-range duration are rejected', () => {
  for (const value of ['2026-02-30', '0000-01-01', '2026-1-01', '2026-10-14T00:00:00Z']) {
    const command = create(); command.p_payload.played_at = value; assert.throws(() => checkCreate(command));
  }
  for (const value of [-1, 1.5, 2147483648]) {
    const command = create(); command.p_payload.duration_minutes = value; assert.throws(() => checkCreate(command));
  }
});
test('Gregorian leap dates and positive four-digit years are accepted', () => {
  for (const value of ['0001-01-01', '2024-02-29', '9999-12-31']) {
    const command = create(); command.p_payload.played_at = value; checkCreate(command);
  }
});
test('all fields are required; extra owner and position are forbidden', () => {
  const absent = create(); delete absent.p_payload.notes; assert.throws(() => checkCreate(absent));
  const owner = create(); owner.p_payload.user_id = id(9); assert.throws(() => checkCreate(owner));
  const position = create(); position.p_payload.scores[0].position = 1; assert.throws(() => checkCreate(position));
  assert.throws(() => validatePlaySave('recalculate_elo', {}));
});
test('participant and score uniqueness are case-insensitive UUID checks', () => {
  const command = create();
  command.p_payload.scores = [{ ...score(), player_id: 'a0000000-0000-0000-0000-000000000002' },
    { ...score(), player_id: 'A0000000-0000-0000-0000-000000000002' }];
  assert.throws(() => checkCreate(command));
  const edit = update(); edit.p_payload.scores = [
    { ...score(), id: id(5) }, { ...score(), id: id(5), player_id: id(6) },
  ]; assert.throws(() => validatePlaySave('update_play_v1', edit));
});
test('malformed identifiers and unexpected source are rejected', () => {
  const command = create(); command.p_request_id = 'not-a-uuid'; assert.throws(() => checkCreate(command));
  command.p_request_id = id(3); command.p_payload.source = 'manual'; assert.throws(() => checkCreate(command));
  command.p_payload.source = 'online'; checkCreate(command);
});
test('create cannot reuse score IDs; update retains ID and metadata', () => {
  const command = create(); command.p_payload.scores[0].id = id(5); assert.throws(() => checkCreate(command));
  const edit = update(); edit.p_payload.scores[0].id = id(5);
  assert.deepEqual(validatePlaySave('update_play_v1', edit).p_payload.scores[0], { ...score(), id: id(5) });
});
test('participant bounds fail explicitly', () => {
  const command = create(); command.p_payload.scores = []; assert.throws(() => checkCreate(command));
  command.p_payload.scores = Array.from({ length: 63 }, (_, i) => ({ ...score(), player_id: id(i + 10) })); checkCreate(command);
  command.p_payload.scores.push({ ...score(), player_id: id(100) }); assert.throws(() => checkCreate(command));
});
test('cooperative outcome must match all participants', () => {
  const command = create(); command.p_payload.team_won = true; assert.throws(() => checkCreate(command));
  command.p_payload.is_cooperative = true; checkCreate(command);
  command.p_payload.scores[0].winner = false; assert.throws(() => checkCreate(command));
  command.p_payload.team_won = null; checkCreate(command);
});
test('metadata rejects PostgreSQL NUL and invalid Unicode', () => {
  for (const value of ['a\0b', '\uD800', '\uDC00']) {
    const command = create(); command.p_payload.notes = value; assert.throws(() => checkCreate(command));
    command.p_payload.notes = null; command.p_payload.scores[0].color = value; assert.throws(() => checkCreate(command));
  }
});
test('payload byte limit accounts for Unicode and jsonb formatting', () => {
  const command = create(); command.p_payload.notes = '';
  const emptyBytes = Buffer.byteLength(jsonbText(command.p_payload));
  command.p_payload.notes = 'a'.repeat(contract.limits.payloadJsonbBytes - emptyBytes); checkCreate(command);
  command.p_payload.notes += 'a'; assert.throws(() => checkCreate(command));
  command.p_payload.notes = '😀'.repeat(270000); assert.throws(() => checkCreate(command));
});
test('revision is bounded and opaque; import positions are explicit', () => {
  for (const revision of ['', ' ', 'x'.repeat(161)]) {
    const edit = update(); edit.p_expected_revision = revision; assert.throws(() => validatePlaySave('update_play_v1', edit));
  }
  const imported = { ...create(), p_metadata: { location: null, bga_table_id: '123' }, p_positions: [null] };
  validatePlaySave('import_bga_play_v1', imported);
  for (const positions of [[], [0], [1.5], [2147483648]]) {
    imported.p_positions = positions; assert.throws(() => validatePlaySave('import_bga_play_v1', imported));
  }
  imported.p_positions = [7];
  for (const tableId of ['', '123 45', 'x'.repeat(257), 'x\0y']) {
    imported.p_metadata.bga_table_id = tableId; assert.throws(() => validatePlaySave('import_bga_play_v1', imported));
  }
});

test('BGA metadata has its own jsonb byte boundary', () => {
  const command = { ...create(), p_metadata: { location: '', bga_table_id: 'boundary' }, p_positions: [null] };
  command.p_metadata.location = 'a'.repeat(contract.limits.metadataJsonbBytes - Buffer.byteLength(jsonbText(command.p_metadata)));
  validatePlaySave('import_bga_play_v1', command);
  command.p_metadata.location += 'a';
  assert.throws(() => validatePlaySave('import_bga_play_v1', command));
});
