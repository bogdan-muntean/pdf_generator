# Data format

> Usually you do not need this page: edit documents in the app (README, "Editing in the app").
> It describes the files for hand editing and for understanding what the app writes.
> The app writes files in a tidy canonical format and keeps previous versions in `data/.history/`.

Every document is one YAML file in the data folder (default `data/`). The file name without
`.yaml` is the document **id** (e.g. `data/cv-en.yaml` → `cv-en`). Images go in `data/assets/`.

There are two kinds of files:

- a **base document**: the full CV or cover letter;
- a **variant**: `extends:` a parent (a base **or another variant**) and lists only the **differences** from it.

Two document types (`meta.type`): `cv` and `cover-letter` (section 4). Both work the same way: base, variants,
editor, undo.

Working examples: [`samples/cv-sample.yaml`](../samples/cv-sample.yaml) (base),
[`samples/cv-sample-qa.yaml`](../samples/cv-sample-qa.yaml) (variant),
[`samples/cv-sample-qa-acme.yaml`](../samples/cv-sample-qa-acme.yaml) (variant of the variant),
[`samples/cover-letter-sample.yaml`](../samples/cover-letter-sample.yaml) and
[`samples/cover-letter-sample-initech.yaml`](../samples/cover-letter-sample-initech.yaml) (cover letter + variant).

---

## 1. Base document

```yaml
meta:
  type: cv                 # required: cv or cover-letter (section 4)
  lang: en                 # default en; sets the PDF language (use ro for Romanian)
  theme: classic           # default classic; folder in src/main/resources/themes/cv/
  outputName: CV_Jane_Doe  # PDF file name without .pdf; default = the document id

person:
  name: Jane Doe           # required
  jobTitle: SOFTWARE DEVELOPER
  photo: assets/photo.jpg  # relative to the data folder; JPG or PNG, cropped to a circle (CV only)

sections:                  # rendered in this order
  - id: summary            # required, unique; letters, digits, - and _
    kind: text             # contact | text | entries | categories | list
    title: EXECUTIVE SUMMARY
    pageBreakBefore: false # optional: start this section on a new page
    items: [...]
```

`person`, `meta` and `letter` are reserved names and cannot be used as section ids.

**Photos uploaded in the app** (*Upload photo…*) are stored in `data/assets/` under a cleaned-up name
(`Poză profil.JPEG` → `poza-profil.jpg`; lowercase letters, digits and `-`). An existing file is never
overwritten: the same picture again reuses the file, a different one gets `-2`, `-3`… Only JPEG and PNG up to
10 MB are accepted, and JPEG location/camera data (EXIF, GPS, XMP, comments) is removed. That also removes the
"rotate" hint some phones store, so a sideways photo stays sideways: rotate it before uploading.

### Section kinds

**`contact`**: icons + values in two columns, with an optional QR code.

```yaml
- id: contact
  kind: contact
  title: CONTACT INFO
  items:
    - { id: location, type: location, value: "Cluj-Napoca, Romania", bold: true }
    - { id: github, type: github, value: /me, href: "https://github.com/me" }
    - { id: email, type: email, label: "Email:", value: me@example.com, bold: true, column: right }
  qr:
    url: https://me.example.com/
    caption: "Scan the QR code or use the link {url}"   # {url} becomes the bold link
```

`type` selects the icon: `location`, `phone`, `email`, `website`, `github`, `linkedin`, `other`.
`href` is optional; for `email`, `phone` and `website` a link is derived automatically.
`column` is `left` (default) or `right`.

**`text`**: paragraphs.

```yaml
items:
  - >-
    A long paragraph can be written on several lines;
    the >- marker joins them with spaces.
```

**`entries`**: jobs, projects, education. The section title repeats on every page it spans.

```yaml
items:
  - id: acme
    heading: Acme – Backend Developer         # required, bold
    subheading: Technical University of Cluj  # optional second bold line
    dates: Jan 2024 – Present                 # optional
    bullets:
      - A plain bullet;
      - { id: acme-api, text: "A bullet a variant can target;" }
```

**`categories`**: labelled lists, rendered as `Label: a; b; c.`

```yaml
items:
  - { id: backend, label: Back-end, values: [Java, "Spring Boot", "PHP (Symfony, Drupal)"] }
```

> ⚠️ Inside `[...]` a comma separates values. Quote any value that contains a comma:
> `"PHP (Symfony, Drupal)"`.

**`list`**: simple bullets (soft skills, languages, ...).

```yaml
items:
  - Adaptability;
  - { id: teamwork, text: Team collaboration. }
```

### Plain text vs `{id, text}`
Bullets, paragraphs and list items can be plain text or `{ id: ..., text: ... }`.
Only those **with an id** can be changed, removed or reordered by a variant. Add ids only where you need them.

---

## 2. Variant

```yaml
extends: cv-en             # the parent: a base or another variant (up to 5 variant levels)

meta:                      # optional: theme, lang, outputName (type cannot change)
  outputName: CV_Jane_Doe_QA

operations:                # applied top to bottom
  - set:    { target: person, fields: { jobTitle: QA ENGINEER } }
  - set:    { target: experience, fields: { title: PROFESSIONAL EXPERIENCE } }
  - set:    { target: experience/acme/acme-api, fields: { text: "Reworded bullet;" } }
  - add:    { into: experience/acme, after: acme-api, bullet: "New bullet;" }
  - add:    { into: skills, before: backend, item: { id: testing, label: Testing, values: [JUnit] } }
  - add:    { after: summary, section: { id: awards, kind: list, title: AWARDS, items: [Prize.] } }
  - remove: { target: projects/old-project }
  - order:  { target: sections, ids: [contact, summary, skills, experience] }
```

### Targets
A target is a path of ids: `sectionId`, `sectionId/itemId` or `sectionId/itemId/bulletId`.
Plus reserved roots for `set` only: `person`, `meta` and (cover letters) `letter`.

| operation | what it does | keys |
|-----------|--------------|------|
| `set`    | replaces the given fields of the target; other fields stay. `unset` removes fields | `target`, `fields` and/or `unset: [field, ...]` (cannot change `id`) |
| `add`    | inserts a `section`, `item` or `bullet` | `into` (omit for sections), `after` or `before` (default: at the end), one of `section` / `item` / `bullet` |
| `remove` | deletes the target | `target` |
| `order`  | reorders children: listed ids first, the others keep their base order (a warning lists them) | `target` (omit or `sections` for sections), `ids` |

Handy recipes:
- **Rename a section:** `set: { target: <section>, fields: { title: NEW TITLE } }`
- **Replace all bullets of an item:** `set: { target: s/item, fields: { bullets: [ ... ] } }`
- **Start a section on a new page:** `set: { target: <section>, fields: { pageBreakBefore: true } }`

### Errors
Mistakes stop rendering with a message naming the file, the operation and the path, for example:

```
Variant 'cv-en-qa', operation #4 (remove): no element with id 'b9' in 'experience/acme'.
Known ids: [acme-api, acme-db]. (Plain-text bullets/items need an id in the base to be targeted: {id: ..., text: ...})
```

The base is validated on its own first, then each level of the chain, so an error is always blamed on the first
file that breaks.

### Variant chains
A variant can extend another variant, for example a general CV, a CV for a role, and a CV for one company:

```
cv-en  ->  cv-en-fullstack  (extends: cv-en)  ->  cv-en-fullstack-luxoft  (extends: cv-en-fullstack)
```

- Each file stores only its differences from its **parent**; in the app, *changed in this variant* and *revert*
  compare with the parent, not with `cv-en`.
- A fix in `cv-en` flows down to every level that does not override that field.
- At most 5 variant levels; a loop (`a` extends `b` extends `a`) is an error naming the chain.
- A variant that other variants extend cannot be deleted in the app (delete its variants first).
- Saving a document warns about every variant below it that no longer applies.

---

## 3. YAML notes
- Values are kept as **text**: `2023-07`, `1.0` or `NO` are never converted to dates, numbers or booleans.
  Only `true` / `false` are booleans.
- Duplicate keys are an error.
- Quote values that start with special characters (`"*"`, `"&"`, `"@"`, `"{"`, `"["`) or contain `: `.

---

## 4. Cover letters

```yaml
meta:
  type: cover-letter
  lang: en
  theme: classic                    # src/main/resources/themes/cover-letter/classic/
  outputName: Jane_Doe_cover_letter

person:
  name: Jane Doe                    # only the name: no jobTitle or photo in a letter

letter:                             # required for cover letters, not allowed in CVs
  salutation: Dear Hiring Manager,  # required, bold
  closing: Sincerely,               # required, bold
  signature: Jane Doe               # optional; default = person.name
  date: 6 November 2026             # optional, free text (never filled in automatically)
  subject: Application for ...      # optional, bold, above the salutation

sections:
  - id: contact                     # sender; at most one contact section
    kind: contact
    title: Sender                   # titles are labels in the editor; the letter does not print them
    items:
      - { id: location, type: location, value: "Cluj-Napoca, Romania" }
      - { id: email, type: email, value: jane@example.com, bold: true }
      - { id: phone, type: phone, value: "+40 700 000 000", bold: true }
      - { id: github, type: github, label: GitHub, value: /jane, href: "https://github.com/jane" }
  - id: recipient
    kind: lines                     # short lines without bullets; the first one is bold
    title: Recipient
    items: [{ id: company, text: "[Company Name]" }, { id: address, text: "[Company Address]" }]
  - id: body
    kind: text                      # the paragraphs
    title: Letter
    items: [{ id: intro, text: "My name is ..." }]
```

**Themes** (`meta.theme`): `classic` (Times New Roman, left-aligned sender) or `modern` (Arial / Liberation
Sans, centred header with the name in capitals, a blue rule, blue recipient line). Both use the same data. A variant
can switch theme with only `meta: { theme: modern }`. An unknown theme is reported on save with the list of available
ones. CVs have `classic`.

In the classic theme, the sender block is printed as: name, then location / email | phone / web links. Web links show their `label`
(or *GitHub*, *LinkedIn*, *Website*). The date goes before the recipient, and subject and salutation before the
first paragraph. Font: Times New Roman (bundled fallback: Liberation Serif).

Allowed section kinds: `contact`, `lines`, `text`. A contact section has no QR code, and `column` is ignored.

**Per company:** keep the `[placeholders]` in the base letter and make a variant per company (`+ variant`) that
changes `recipient/company`, `recipient/address`, the paragraphs that differ, and maybe `letter.subject`:

```yaml
extends: cover-letter-en
operations:
  - set: { target: recipient/company, fields: { text: Luxoft Romania } }
  - set: { target: letter, fields: { subject: "Application for the Java Developer position" } }
  - set: { target: body/intro, fields: { text: "My name is ... at Luxoft ..." } }
```
