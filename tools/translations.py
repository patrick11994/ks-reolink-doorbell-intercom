#!/usr/bin/env python3
"""Keeps the translations in step with English, and the README's progress table up to date.

Every language file in assets/i18n follows en.properties line by line. A text it has not translated yet is a
'# TODO key=<English>' line, and a translation whose English text changed since gets a '# TODO update' line above it
with the English it now has, so a translator only fills in or fixes the TODO lines. Which English text each
translation was made from is kept in tools/i18n-sources/<code>.properties, which translators never see.

    python3 tools/translations.py          rewrites the language files, the sources and the README table
    python3 tools/translations.py --check  only checks that they are up to date (the tests run this)

After adding a file from a translator, run it once: texts they translated count as made from today's English.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
I18N = ROOT / 'assets' / 'i18n'
SOURCES = ROOT / 'tools' / 'i18n-sources'
README = ROOT / 'README.md'
TABLE_START, TABLE_END = '<!-- translations -->', '<!-- /translations -->'
TODO, UPDATE = '# TODO ', '# TODO update, English now: '
REPO = 'https://github.com/patrick11994/ks-reolink-doorbell-intercom'
LINE = re.compile(r'^([A-Za-z0-9_.-]+)=(.*)$')


def read(path):
    """The key=value lines of a file, in order; comment lines are left out."""
    texts = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            m = LINE.match(line)
            if m:
                texts[m.group(1)] = m.group(2)
    return texts


def outdated(path):
    """The keys a '# TODO update' line stands above in a language file."""
    keys, flagged = set(), False
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if line.startswith(UPDATE):
                flagged = True
                continue
            m = LINE.match(line)
            if m and flagged:
                keys.add(m.group(1))
            flagged = False
    return keys


def english_body():
    """en.properties after its header comment: the section comments and key=value lines in order."""
    lines = (I18N / 'en.properties').read_text(encoding='utf-8').splitlines()
    start = next(i for i, line in enumerate(lines) if not line.startswith('#'))
    return lines[start:]


def languages():
    """Code to name, in the Language setting's order, English left out."""
    return {code: name for code, name in read(I18N / 'languages.properties').items() if code != 'en'}


def sync(code, name, english):
    """The language file's new text, its sources' new text, and its counts (translated, out of date)."""
    path = I18N / f'{code}.properties'
    texts, flagged = read(path), outdated(path)
    sources = read(SOURCES / f'{code}.properties')
    out = [f"# Reolink Doorbell Intercom: the plugin's own texts in {name}. See the README's Translations.",
           '# A line starting with "# TODO key=" still needs translating: remove "# TODO ", then translate after the =.',
           '# A "# TODO update" line means the English text changed: fix the translation below it, then remove that line.',
           '# Keep the {placeholders} as they are.']
    kept, translated, stale = {}, 0, 0
    for line in english_body():
        m = LINE.match(line)
        if not m:
            out.append(line)  # a blank line or a section comment
            continue
        key, source = m.groups()
        if key not in texts:
            out.append(f'{TODO}{key}={source}')
            continue
        translated += 1
        # Out of date: flagged in the file already, or its English changed since it was made. Once flagged, the flag
        # alone keeps it so, and the source moves on to today's English: a translator who fixes the text removes the
        # flag, and from then on it counts as current. A text without a source yet (a first run, or a file a translator
        # just sent) counts as made from today's English.
        if key in flagged or sources.get(key, source) != source:
            stale += 1
            out.append(f'{UPDATE}{source}')
        kept[key] = source
        out.append(f'{key}={texts[key]}')
    source_text = '\n'.join([f'# The English text each {code} translation was made from; see tools/translations.py.']
                            + [f'{key}={text}' for key, text in kept.items()]) + '\n'
    return '\n'.join(out) + '\n', source_text, translated, stale


def table(rows, total):
    lines = [TABLE_START, '', '| Language | Translated | Out of date | File |', '| --- | --- | --- | --- |']
    for code, name, translated, stale in rows:
        percent = 100 * translated // total if translated < total else 100
        file = f'[`{code}.properties`]({REPO}/blob/main/assets/i18n/{code}.properties)'
        lines.append(f'| {name} | {percent}% ({translated} of {total}) | {stale or "–"} | {file} |')
    return '\n'.join(lines + ['', TABLE_END])


def main(check):
    english = read(I18N / 'en.properties')
    rows, changed = [], []
    wanted = {}
    for code, name in languages().items():
        text, source_text, translated, stale = sync(code, name, english)
        wanted[I18N / f'{code}.properties'] = text
        wanted[SOURCES / f'{code}.properties'] = source_text
        rows.append((code, name, translated, stale))
    readme = README.read_text(encoding='utf-8')
    if TABLE_START not in readme or TABLE_END not in readme:
        sys.exit(f'README.md has no {TABLE_START} … {TABLE_END} block for the table')
    before, rest = readme.split(TABLE_START, 1)
    wanted[README] = before + table(rows, len(english)) + rest.split(TABLE_END, 1)[1]
    for path, text in wanted.items():
        if not path.exists() or path.read_text(encoding='utf-8') != text:
            changed.append(path)
            if not check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding='utf-8')
    if check and changed:
        names = ', '.join(str(p.relative_to(ROOT)) for p in changed)
        sys.exit(f'Translations are not up to date ({names}): run python3 tools/translations.py')
    for code, name, translated, stale in rows:
        print(f'{name}: {translated} of {len(english)} translated, {stale} out of date')


if __name__ == '__main__':
    main('--check' in sys.argv[1:])
