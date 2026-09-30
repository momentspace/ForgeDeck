"""Build a dependency-free distributable and the in-chat preview.

Usage: python prototype/build.py [--lucide /absolute/path/to/lucide.min.js]
The source files remain the authority. No requests are made by this script.
"""
from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--lucide', type=Path, default=Path(__file__).parent / 'vendor/lucide.min.js')
parser.add_argument('--preview', type=Path)
args = parser.parse_args()
directory = Path(__file__).resolve().parent
css = (directory / 'styles.css').read_text()
shell = (directory / 'shell.html').read_text()
js = (directory / 'app.js').read_text()
fragment = f'<style>\n{css}\n</style>\n{shell}\n<script>\n{js}\n</script>\n'
vendor = args.lucide.read_text()
html = '<!doctype html>\n<html lang="ja">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1">\n<title>ForgeDeck — UI prototype</title>\n<style>html{color-scheme:light dark}body{margin:0;background:light-dark(#edf1f7,#080d13)}\n' + css + '</style>\n</head>\n<body>\n' + shell + '\n<script>\n' + vendor + '\n</script>\n<script>\n' + js + '\n</script>\n</body>\n</html>\n'
(directory / 'index.html').write_text(html)
if args.preview:
    args.preview.write_text(fragment)
print(f'Built {directory / "index.html"}: {len(html.encode()):,} bytes')
