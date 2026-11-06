import { canRedo, canUndo, createHistory, push, redo, replacePresent, undo } from './history';

const equals = (a: string, b: string) => a === b;
const step = (h: ReturnType<typeof createHistory<string>>, value: string, now: number, group?: string) =>
  push(h, value, { now, group, equals });

describe('history', () => {
  it('undoes and redoes step by step', () => {
    let h = createHistory('a');
    h = step(h, 'b', 0);
    h = step(h, 'c', 5000);
    expect(canRedo(h)).toBe(false);

    h = undo(h);
    expect(h.present).toBe('b');
    h = undo(h);
    expect(h.present).toBe('a');
    expect(canUndo(h)).toBe(false);
    expect(undo(h)).toBe(h); // nothing more to undo

    h = redo(h);
    h = redo(h);
    expect(h.present).toBe('c');
    expect(redo(h)).toBe(h);
  });

  it('a new change after undo drops the redo branch and becomes the latest step', () => {
    let h = createHistory('a');
    h = step(h, 'b', 0);
    h = step(h, 'c', 5000);
    h = undo(h); // present b, future [c]
    h = step(h, 'x', 9000);
    expect(h.present).toBe('x');
    expect(canRedo(h)).toBe(false);
    expect(undo(h).present).toBe('b');
  });

  it('merges typing in the same field into one step, but not across fields or pauses', () => {
    let h = createHistory('');
    h = step(h, 'H', 0, 'person.name');
    h = step(h, 'He', 300, 'person.name');
    h = step(h, 'Hey', 600, 'person.name');
    expect(h.past).toEqual(['']);

    h = step(h, 'Hey!', 600 + 5000, 'person.name'); // after a pause: new step
    h = step(h, 'Hey!?', 6000, 'person.jobTitle'); // other field: new step
    h = step(h, 'Hey!?.', 6100); // a button: always its own step
    expect(h.past).toEqual(['', 'Hey', 'Hey!', 'Hey!?']);
  });

  it('starts a new step after undo even in the same field', () => {
    let h = createHistory('');
    h = step(h, 'a', 0, 'f');
    h = undo(h);
    h = step(h, 'b', 100, 'f');
    expect(h.past).toEqual(['']);
    expect(h.present).toBe('b');
  });

  it('replacing the present (after save) keeps undo and redo and ends the typing run', () => {
    let h = createHistory('a');
    h = step(h, 'b', 0, 'f');
    h = replacePresent(h, 'b+ids');
    expect(h.present).toBe('b+ids');
    expect(undo(h).present).toBe('a');
    h = step(h, 'c', 100, 'f'); // same field right after save: still a new step
    expect(h.past).toEqual(['a', 'b+ids']);
  });

  it('ignores no-op changes and caps its size', () => {
    let h = createHistory('a');
    expect(step(h, 'a', 0)).toBe(h);
    for (let i = 0; i < 10; i++) h = push(h, `v${i}`, { now: i * 5000, equals, limit: 3 });
    expect(h.past).toEqual(['v6', 'v7', 'v8']);
  });
});
