import re
import os

def update_roadmap():
    changelog_path = 'CHANGELOG.md'
    index_path = 'docs/index.html'

    if not os.path.exists(changelog_path) or not os.path.exists(index_path):
        print("Required files not found.")
        return

    with open(changelog_path, 'r', encoding='utf-8') as f:
        changelog_content = f.read()

    # Match version blocks like ## [2.0.1] - 2026-09-14
    # and grab the content until the next version block or end of file
    version_pattern = r'## \[(.*?)\] - (\d{4}-\d{2}-\d{2})\n\n(.*?)(?=\n## |$)'
    matches = re.findall(version_pattern, changelog_content, re.DOTALL)

    if not matches:
        print("No versions found in changelog.")
        return

    # Take the latest 2 versions for the timeline
    latest_releases = matches[:2]

    new_timeline_html = '    <section id="timeline" class="timeline-section">\n'
    new_timeline_html += '        <div class="container">\n'
    new_timeline_html += '            <h2 class="section-title reveal">Roadmap & Changelog</h2>\n'
    new_timeline_html += '            <div class="timeline reveal">\n'

    for version, date, desc in latest_releases:
        # Clean up the description: take the first few bullet points or lines
        clean_desc = desc.strip().replace('### Added\n', '').replace('### Fixed\n', '').replace('### Changed\n', '')
        # Take first 2 lines and clean up markdown bullets
        summary = ". ".join([line.strip('- ').strip() for line in clean_desc.split('\n') if line.strip()][:2])

        # Format date to Month Year
        from datetime import datetime
        date_obj = datetime.strptime(date, '%Y-%m-%d')
        formatted_date = date_obj.strftime('%B %Y')

        new_timeline_html += f'                <div class="timeline-item">\n'
        new_timeline_html += f'                    <div class="timeline-content">\n'
        new_timeline_html += f'                        <h3>v{version}</h3>\n'
        new_timeline_html += f'                        <p>{summary}.</p>\n'
        new_timeline_html += f'                        <span class="date">{formatted_date}</span>\n'
        new_timeline_html += f'                    </div>\n'
        new_timeline_html += f'                </div>\n'

    # Add a "Future" item
    new_timeline_html += '                <div class="timeline-item">\n'
    new_timeline_html += '                    <div class="timeline-content">\n'
    new_timeline_html += '                        <h3>Next Gen</h3>\n'
    new_timeline_html += '                        <p>Planned support for audio visualizer templates and cloud backup integration.</p>\n'
    new_timeline_html += '                        <span class="date">Future Roadmap</span>\n'
    new_timeline_html += '                    </div>\n'
    new_timeline_html += '                </div>\n'
    new_timeline_html += '            </div>\n'
    new_timeline_html += '        </div>\n'
    new_timeline_html += '    </section>'

    with open(index_path, 'r', encoding='utf-8') as f:
        index_content = f.read()

    # Use regex to replace the entire <section id="timeline">...</section> block
    section_pattern = r'<section id="timeline".*?</section>'
    updated_index = re.sub(section_pattern, new_timeline_html, index_content, flags=re.DOTALL)

    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(updated_index)

    print("Roadmap updated successfully in index.html")

if __name__ == "__main__":
    update_roadmap()
