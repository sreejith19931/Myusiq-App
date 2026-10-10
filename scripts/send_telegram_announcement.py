import os
import re
import json
import urllib.request

def escape_html(text):
    if not text:
        return ""
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')

def format_markdown_bold(text):
    parts = re.split(r'(\*\*.*?\*\*)', text)
    res = []
    for part in parts:
        if part.startswith('**') and part.endswith('**'):
            inner = escape_html(part[2:-2])
            res.append(f'<b>{inner}</b>')
        else:
            res.append(escape_html(part))
    return ''.join(res)

def send_telegram_announcement():
    bot_token = os.environ.get('TELEGRAM_BOT_TOKEN')
    chat_id = os.environ.get('TELEGRAM_CHAT_ID')
    tag = os.environ.get('TAG_NAME', '')
    if not tag.startswith('v') and not tag.startswith('V') and tag:
        tag = f'v{tag}'

    release_url = os.environ.get('RELEASE_URL', f'https://github.com/sreejith19931/Myusiq-App/releases/tag/{tag}')
    apk_url = os.environ.get('APK_URL', f'https://github.com/sreejith19931/Myusiq-App/releases/download/{tag}/Myusiq-{tag}.apk')
    website_url = os.environ.get('WEBSITE_URL', 'https://myusiq.vercel.app')

    if not bot_token or not chat_id:
        print("Telegram credentials (TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID) missing. Skipping announcement.")
        return

    changelog_items = []
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    changelog_path = os.path.join(root_dir, 'CHANGELOG.md')

    try:
        if os.path.exists(changelog_path):
            with open(changelog_path, 'r', encoding='utf-8') as f:
                content = f.read()
            blocks = re.split(r'\n(?=##\s+\[)', content)
            for block in blocks:
                if block.strip().startswith('## ['):
                    lines = [l.strip() for l in block.split('\n') if l.strip().startswith('- ')]
                    for line in lines:
                        cleaned = line[2:].strip()
                        formatted_line = format_markdown_bold(cleaned)
                        changelog_items.append(f'• {formatted_line}')
                    if changelog_items:
                        break
    except Exception as e:
        print('Changelog parse error:', e)

    changelog_str = '\n'.join(changelog_items) if changelog_items else '• General improvements, bug fixes, and performance updates.'

    message = f"""🚀 <b>Myusiq {tag} is now live!</b>

📋 <b>Changelog:</b>
{changelog_str}

⚡ <i>Studio-grade audio player with Material You dynamic themes. 100% open-source & offline.</i>"""

    reply_markup = {
        'inline_keyboard': [
            [
                {'text': '⚡ Direct APK Download', 'url': apk_url},
                {'text': '🌐 Official Website', 'url': website_url}
            ],
            [
                {'text': '📦 View Release Notes on GitHub', 'url': release_url}
            ]
        ]
    }

    url = f'https://api.telegram.org/bot{bot_token}/sendMessage'
    payload = {
        'chat_id': chat_id,
        'text': message,
        'parse_mode': 'HTML',
        'disable_web_page_preview': False,
        'reply_markup': reply_markup
    }

    try:
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req) as resp:
            print('Telegram notification sent successfully. Status:', resp.getcode())
    except Exception as err:
        print('Telegram notification error:', err)

if __name__ == '__main__':
    send_telegram_announcement()
