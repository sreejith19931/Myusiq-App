import re
import os
from datetime import datetime

# Keywords that indicate internal backend / CI / developer-only lines to filter out from public changelog
BACKEND_KEYWORDS = [
    'ci/cd', 'github actions', 'fallbacktodestructivemigration', 'schema',
    'version alignment', 'release key', 'workflow', 'build configuration',
    'python script', 'room database integrity', 'script'
]

def is_feature_line(line):
    line_lower = line.lower()
    for kw in BACKEND_KEYWORDS:
        if kw in line_lower:
            return False
    return True

def format_markdown_to_html(text):
    # Convert bold **text** to <strong>text</strong>
    text = re.sub(r'\*\*(.*?)\*\*', r'<strong>\1</strong>', text)
    # Convert code `text` to <code>text</code>
    text = re.sub(r'`(.*?)`', r'<code>\1</code>', text)
    return text

def update_roadmap():
    changelog_path = 'CHANGELOG.md'
    index_path = 'docs/index.html'

    if not os.path.exists(changelog_path) or not os.path.exists(index_path):
        print("Required files not found.")
        return

    with open(changelog_path, 'r', encoding='utf-8') as f:
        changelog_content = f.read()

    # Match version blocks like ## [2.1.0] - 2026-10-06
    version_pattern = r'## \[(.*?)\] - (\d{4}-\d{2}-\d{2})\n\n(.*?)(?=\n## |$)'
    matches = re.findall(version_pattern, changelog_content, re.DOTALL)

    if not matches:
        print("No versions found in changelog.")
        return

    # Take the latest releases for the timeline
    latest_releases = matches[:4]

    new_timeline_html = '    <section id="timeline" class="timeline-section">\n'
    new_timeline_html += '        <div class="container">\n'
    new_timeline_html += '            <div class="section-header reveal">\n'
    new_timeline_html += '                <span class="section-badge">Roadmap & History</span>\n'
    new_timeline_html += '                <h2 class="section-title">What\'s New in Myusiq</h2>\n'
    new_timeline_html += '                <p class="section-subtitle">Track the latest user features, design updates, and audio enhancements.</p>\n'
    new_timeline_html += '            </div>\n'
    new_timeline_html += '            <div class="timeline reveal">\n'

    for version, date, desc in latest_releases:
        # Extract markdown bullet lines
        raw_lines = [line.strip('- ').strip() for line in desc.strip().split('\n') if line.strip() and not line.startswith('###')]

        # Filter out backend/internal technical lines
        feature_lines = [line for line in raw_lines if is_feature_line(line)]

        # Fallback if all lines were filtered out
        if not feature_lines and raw_lines:
            feature_lines = raw_lines[:2]

        if not feature_lines:
            continue

        bullets_html = ""
        for line in feature_lines:
            html_line = format_markdown_to_html(line)
            bullets_html += f'                            <li>{html_line}</li>\n'

        # Format date to Month Year
        date_obj = datetime.strptime(date, '%Y-%m-%d')
        formatted_date = date_obj.strftime('%b %d, %Y')

        new_timeline_html += f'                <div class="timeline-item">\n'
        new_timeline_html += f'                    <div class="timeline-dot"></div>\n'
        new_timeline_html += f'                    <div class="timeline-content glass-card">\n'
        new_timeline_html += f'                        <div class="timeline-header">\n'
        new_timeline_html += f'                            <span class="version-badge">v{version}</span>\n'
        new_timeline_html += f'                            <span class="date">{formatted_date}</span>\n'
        new_timeline_html += f'                        </div>\n'
        new_timeline_html += f'                        <ul class="changelog-list">\n'
        new_timeline_html += f'{bullets_html}'
        new_timeline_html += f'                        </ul>\n'
        new_timeline_html += f'                    </div>\n'
        new_timeline_html += f'                </div>\n'

    new_timeline_html += '            </div>\n'
    new_timeline_html += '        </div>\n'
    new_timeline_html += '    </section>'

    with open(index_path, 'r', encoding='utf-8') as f:
        index_content = f.read()

    section_pattern = r'<section id="timeline".*?</section>'
    updated_index = re.sub(section_pattern, new_timeline_html, index_content, flags=re.DOTALL)

    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(updated_index)

    print("Changelog updated successfully in index.html (feature-focused)")

if __name__ == "__main__":
    update_roadmap()
