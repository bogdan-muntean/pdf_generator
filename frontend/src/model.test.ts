import {
  type Doc, idsForNewTextItems, move, newSection, overrideOf, renameFresh, same, setOptional, slug, stripTransient,
  uniqueId,
} from './model';

const base: Doc = {
  meta: { type: 'cv' },
  person: { name: 'Jane' },
  sections: [
    { id: 'skills', kind: 'list', title: 'SKILLS', items: ['Java;', { id: 'sql', text: 'SQL.' }] },
    { id: 'exp', kind: 'entries', title: 'EXP', items: [{ id: 'acme', heading: 'Acme', bullets: ['Old plain;'] }] },
  ],
};

describe('ids', () => {
  it('slugs like the server', () => {
    expect(slug('Ștefan cel Mare – Școala')).toBe('stefan-cel-mare-scoala');
    expect(slug('Developed and maintained features for a hybrid app', 4)).toBe('developed-and-maintained-features');
    expect(slug(' ;; ')).toBe('item');
  });

  it('makes ids unique among siblings', () => {
    expect(uniqueId('a', ['a', 'a-2'])).toBe('a-3');
    expect(uniqueId('b', ['a'])).toBe('b');
  });

  it('lets fresh elements follow their title, but never saved ones', () => {
    const sections = [{ id: 'projects' }, { id: 'new-section', _fresh: true }];
    renameFresh(sections[1], 'Projects', sections, 'section');
    expect(sections[1].id).toBe('projects-2');
    const saved = { id: 'fixed' };
    renameFresh(saved, 'Other', [saved], 'section');
    expect(saved.id).toBe('fixed');
  });

  it('gives ids only to new plain text items of a variant', () => {
    const edited: Doc = structuredClone(base);
    (edited.sections[0].items as unknown[]).push('Kotlin;');
    const exp = edited.sections[1];
    if (exp.kind === 'entries') exp.items[0].bullets!.push('New bullet here;');
    const result = idsForNewTextItems(edited, base);
    expect(result.sections[0].items).toEqual(['Java;', { id: 'sql', text: 'SQL.' }, { id: 'kotlin', text: 'Kotlin;' }]);
    expect((result.sections[1].items[0] as { bullets: unknown[] }).bullets)
      .toEqual(['Old plain;', { id: 'new-bullet-here', text: 'New bullet here;' }]);
  });
});

describe('comparison', () => {
  it('ignores key order and editor markers', () => {
    expect(same({ a: 1, b: [1, { c: 2 }] }, { b: [1, { c: 2 }], a: 1 })).toBe(true);
    expect(same({ id: 'x', _fresh: true }, { id: 'x' })).toBe(true);
    expect(same([1, 2], [2, 1])).toBe(false);
  });

  it('classifies variant elements', () => {
    expect(overrideOf({ id: 'a', t: 1 }, undefined)).toBe('new');
    expect(overrideOf({ id: 'a', t: 1 }, { t: 1, id: 'a' })).toBe('inherited');
    expect(overrideOf({ id: 'a', t: 2 }, { id: 'a', t: 1 })).toBe('changed');
  });
});

describe('edits', () => {
  it('moves within bounds only', () => {
    const list = ['a', 'b', 'c'];
    move(list, 0, -1);
    move(list, 0, 1);
    expect(list).toEqual(['b', 'a', 'c']);
  });

  it('removes blank optional fields', () => {
    const entry: { dates?: string } = { dates: 'x' };
    setOptional(entry, 'dates', '  ');
    expect(entry).toEqual({});
  });

  it('creates sections with unique fresh ids and strips markers', () => {
    const section = newSection('list', 'Skills', base.sections);
    expect(section.id).toBe('skills-2');
    expect(stripTransient(section)).toEqual({ id: 'skills-2', title: 'Skills', kind: 'list', items: [''] });
  });
});
