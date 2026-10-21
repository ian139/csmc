#!/usr/bin/env python3
"""Export a CS2 weapon case inventory icon from your own install into the csmc cache.

Writes <cache>/case/<item>.png (the decoded econ icon) and <cache>/case/<item>.source.json, where <item> is the csmc
case item the icon is for. Valve art stays in the external cache; nothing is written into the repository.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cs2_skins import Vpk  # noqa: E402

ICON_DIR = 'panorama/images/econ/weapon_cases/'
DEFAULT_CASE = 'crate_valve_1'  # the original "CS:GO Weapon Case"
CASE_ITEMS = ('weapon_case', 'inventory_weapon_case')
PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'


def worktree_root():
    """The enclosing git worktree (a `.git` dir or file), or the payload directory outside git."""
    here = Path(__file__).resolve().parent
    for directory in (here, *here.parents):
        if (directory / '.git').exists():
            return directory
    return here.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vpk', default='~/csmc-assets/cs2/game/csgo/pak01_dir.vpk')
    parser.add_argument('--cli', default=os.environ.get('CSMC_S2V_CLI') or shutil.which('Source2Viewer-CLI')
                        or '~/csmc-assets/tools/s2v/Source2Viewer-CLI')
    parser.add_argument('--cache', default=os.environ.get('CSMC_CACHE') or '~/.csmc/cache')
    parser.add_argument('--case', default=DEFAULT_CASE,
                        help='econ icon name under ' + ICON_DIR + ' without _png.vtex_c (default: %(default)s)')
    parser.add_argument('--item', default=CASE_ITEMS[0], choices=CASE_ITEMS,
                        help='csmc case item the icon is for (default: %(default)s)')
    parser.add_argument('--list', action='store_true', help='print the econ case icon names in the VPK and exit')
    args = parser.parse_args()
    vpk_path, cli, cache = (Path(value).expanduser().resolve() for value in (args.vpk, args.cli, args.cache))
    if args.list:
        if not vpk_path.is_file():
            parser.error('provide an existing --vpk')
        suffix = '_png.vtex_c'
        for name in sorted(p[len(ICON_DIR):-len(suffix)] for p in Vpk(vpk_path).entries
                           if p.startswith(ICON_DIR) and p.endswith(suffix)):
            print(name)
        return 0
    repo = worktree_root()
    if cache == repo or cache.is_relative_to(repo):
        parser.error('cache must be outside the repository; exported Valve assets must not be committed')
    if not vpk_path.is_file() or not cli.is_file() or not os.access(cli, os.X_OK):
        parser.error('provide an existing --vpk and executable --cli')

    source = ICON_DIR + args.case + '_png.vtex_c'
    vpk = Vpk(vpk_path)
    if source not in vpk.entries:
        print('Not in VPK index: ' + source, file=sys.stderr)
        return 1
    if not vpk.present(source):
        print('Missing archive chunk for ' + source + ': ' + vpk.chunk(source).name, file=sys.stderr)
        return 1

    target = cache / 'case'
    icon, record = target / (args.item + '.png'), target / (args.item + '.source.json')
    destinations = (target, icon, record)
    if any(path.is_symlink() for path in destinations):
        parser.error('cache case directory and files must not be symlinks; exports must stay inside the external cache')
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.export-', dir=target) as scratch:
        done = subprocess.run([str(cli), '-i', str(vpk_path), '-d', '-f', source, '-o', scratch],
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        decoded = Path(scratch) / (source[:-len('.vtex_c')] + '.png')
        if done.returncode or not decoded.is_file() or decoded.read_bytes()[:8] != PNG_SIGNATURE:
            print(done.stdout, file=sys.stderr)
            print('Source2Viewer-CLI did not produce a PNG for ' + source, file=sys.stderr)
            return 1
        os.replace(decoded, icon)
    record.write_text(json.dumps({'vpk_path': source, 'case': args.case, 'item': args.item}, indent=2) + '\n')
    print('Wrote ' + str(icon))
    return 0


if __name__ == '__main__':
    sys.exit(main())
