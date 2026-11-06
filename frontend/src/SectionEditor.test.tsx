import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { EditorContext } from './EditorContext';
import { SectionEditor } from './SectionEditor';
import type { Rules } from './api';
import type { Doc, Problem } from './model';

const CV_RULES: Rules = {
  sectionKinds: ['contact', 'text', 'entries', 'categories', 'list'], maxContact: 99, letter: false, qrCode: true,
  photo: true, jobTitle: true, themes: ['classic'],
};

const doc: Doc = {
  meta: { type: 'cv' },
  person: { name: 'Jane' },
  sections: [{
    id: 'experience', kind: 'entries', title: 'WORK EXPERIENCE',
    items: [{ id: 'acme', heading: 'Acme – Dev', dates: '2024', bullets: [{ id: 'b1', text: 'Built things;' }] }],
  }],
};

let latest: Doc = doc;

function Harness({ base, problems = [] }: { base?: Doc; problems?: Problem[] }) {
  const [draft, setDraft] = useState<Doc>(structuredClone(doc));
  latest = draft;
  const update = (mutate: (d: Doc) => void) => setDraft((prev) => {
    const next = structuredClone(prev);
    mutate(next);
    return next;
  });
  return (
    <EditorContext.Provider value={{ update, problems, base, parentId: base ? 'cv' : undefined, images: [], addImage: () => {}, rules: CV_RULES }}>
      <SectionEditor section={draft.sections[0]} index={0} count={1} />
    </EditorContext.Provider>
  );
}

describe('SectionEditor', () => {
  it('edits titles, entry fields and bullets in the draft', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    const title = screen.getByLabelText(/Section title/);
    await user.clear(title);
    await user.type(title, 'EXPERIENCE');
    await user.click(screen.getByRole('button', { name: '+ bullet' }));
    await user.type(screen.getByLabelText('Bullet 2'), 'Shipped more;');
    await user.clear(screen.getByLabelText('Dates (optional)'));

    const section = latest.sections[0];
    expect(section.title).toBe('EXPERIENCE');
    if (section.kind !== 'entries') throw new Error('kind');
    expect(section.items[0].bullets).toEqual([{ id: 'b1', text: 'Built things;' }, 'Shipped more;']);
    expect(section.items[0]).not.toHaveProperty('dates');
  });

  it('shows server problems on the matching field', () => {
    render(<Harness problems={[{ path: 'sections[experience].items[acme].heading', message: 'is required' }]} />);
    expect(screen.getByText('is required')).toBeInTheDocument();
  });

  it('marks variant overrides and reverts them to the base', async () => {
    const user = userEvent.setup();
    const base = structuredClone(doc);
    if (base.sections[0].kind === 'entries') base.sections[0].items[0].heading = 'Acme – Junior Dev';
    render(<Harness base={base} />);

    expect(screen.getAllByText('changed in this variant')).toHaveLength(2); // section + entry
    await user.click(screen.getAllByRole('button', { name: 'revert' })[1]);
    expect(latest.sections[0]).toEqual(base.sections[0]);
  });
});
