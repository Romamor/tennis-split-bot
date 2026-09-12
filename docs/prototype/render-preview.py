"""Wrap the committed fragment for a local browser or Playwright inspection."""
from pathlib import Path
from html import escape
import sys

source = Path(__file__).with_name('tennis-flow.html').read_text()
destination = Path(sys.argv[1])
inner = '<html lang="ru"><meta charset="utf-8"><style>:root{color-scheme:light dark}body{margin:0;padding:16px;font-family:system-ui}</style>' + source + '</html>'
destination.write_text('<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Макет Telegram-бота</title><style>:root{color-scheme:light dark}body{margin:0}iframe{width:100%;height:100vh;border:0}</style><iframe title="Макет Telegram-бота" sandbox="allow-scripts" srcdoc="' + escape(inner, quote=True) + '"></iframe></html>')
print(destination)
