import re
import os
import sys

def parse_changelog(changelog_path):
    if not os.path.exists(changelog_path):
        print(f"Changelog not found at {changelog_path}")
        return []

    with open(changelog_path, 'r', encoding='utf-8') as f:
        content = f.read()

    version_blocks = re.split(r'\n(?=##\s+\[)', content)
    releases = []

    for block in version_blocks:
        match = re.search(r'##\s+\[(.*?)\]\s*-\s*([\d-]+)', block)
        if not match:
            continue

        version = match.group(1).strip()
        date_str = match.group(2).strip()

        # Format date string (e.g. 2026-10-10 -> Oct 10, 2026)
        months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        formatted_date = date_str
        try:
            parts = date_str.split('-')
            if len(parts) == 3:
                year, month_idx, day = parts
                month_name = months[int(month_idx) - 1]
                formatted_date = f"{month_name} {int(day):02d}, {year}"
        except Exception:
            pass

        items = []
        for line in block.split('\n'):
            line = line.strip()
            if line.startswith('- '):
                item_text = line[2:].strip()
                items.append(item_text)

        releases.append({
            "version": version,
            "date": formatted_date,
            "items": items
        })

    return releases

def markdown_to_html(text):
    # Convert bold markdown **bold** to <strong>bold</strong>
    return re.sub(r'\*\*(.*?)\*\*', r'<strong>\1</strong>', text)

def is_major_release(version):
    # Version is major if it ends in .0.0 or minor/patch is 0 or X.0.0 format (e.g. 1.0.0, 2.0.0)
    version = version.lstrip('v')
    parts = version.split('.')
    if len(parts) >= 2:
        return parts[1] == '0' and (len(parts) < 3 or parts[2] == '0')
    return False

def update_website():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    changelog_path = os.path.join(root_dir, 'CHANGELOG.md')
    index_path = os.path.join(root_dir, 'docs', 'index.html')

    releases = parse_changelog(changelog_path)
    if not releases:
        print("No releases found in CHANGELOG.md")
        return

    latest_release = releases[0]
    latest_version = latest_release['version']
    clean_version = latest_version.lstrip('v')

    print(f"Latest release from CHANGELOG: v{clean_version}")

    if not os.path.exists(index_path):
        print(f"docs/index.html not found at {index_path}")
        return

    with open(index_path, 'r', encoding='utf-8') as f:
        html = f.read()

    # 1. Update version buttons and download links
    html = re.sub(
        r'Get APK \(v[\d\.]+\)',
        f'Get APK (v{clean_version})',
        html
    )
    html = re.sub(
        r'Download APK \(v[\d\.]+\)',
        f'Download APK (v{clean_version})',
        html
    )
    html = re.sub(
        r'Download Latest Release \(v[\d\.]+\)',
        f'Download Latest Release (v{clean_version})',
        html
    )
    html = re.sub(
        r'Download Myusiq-v[\d\.]+\.apk',
        f'Download Myusiq-v{clean_version}.apk',
        html
    )
    html = re.sub(
        r'<code>Myusiq-v[\d\.]+\.apk</code>',
        f'<code>Myusiq-v{clean_version}.apk</code>',
        html
    )
    html = re.sub(
        r'releases/tag/v[\d\.]+',
        f'releases/tag/v{clean_version}',
        html
    )

    # 2. Build Timeline HTML
    timeline_items = []
    for rel in releases:
        items_html = "\n".join([f"                            <li>{markdown_to_html(it)}</li>" for it in rel['items']])
        item_block = f"""                <div class="timeline-item">
                    <div class="timeline-dot"></div>
                    <div class="timeline-content glass-card">
                        <div class="timeline-header">
                            <span class="version-badge">v{rel['version']}</span>
                            <span class="date">{rel['date']}</span>
                        </div>
                        <ul class="changelog-list">
{items_html}
                        </ul>
                    </div>
                </div>"""
        timeline_items.append(item_block)

    timeline_html = "\n".join(timeline_items)

    # Replace timeline section content
    timeline_pattern = r'(<div class="timeline reveal">)[\s\S]*?(<\/div>\s*<\/div>\s*<\/section>)'
    replacement = f'\\1\n{timeline_html}\n            \\2'
    html = re.sub(timeline_pattern, replacement, html)

    # 3. Build Latest Release Blog Post Section Above 'Crafted for Excellence'
    blog_items_html = "\n".join([f"                            <li>{markdown_to_html(it)}</li>" for it in latest_release['items']])
    blog_post_html = f"""    <!-- Latest Release Blog Announcement -->
    <section id="latest-blog-post" class="blog-announcement-section">
        <div class="container">
            <div class="glass-card blog-card reveal">
                <div class="blog-header">
                    <div class="blog-badge-wrap">
                        <span class="blog-badge">🚀 LATEST UPDATE</span>
                        <span class="blog-version">v{clean_version}</span>
                    </div>
                    <span class="blog-date">{latest_release['date']}</span>
                </div>
                <h2 class="blog-title">What's New in Myusiq v{clean_version}</h2>
                <div class="blog-body">
                    <p class="blog-intro">We are excited to announce the official release of <strong>Myusiq v{clean_version}</strong>! This update brings key feature additions, performance optimizations, and important usability upgrades to your audio experience.</p>
                    <div class="blog-highlights-box">
                        <h4 class="blog-highlights-title">✨ Key Features &amp; Changes Implemented</h4>
                        <ul class="blog-items-list">
{blog_items_html}
                        </ul>
                    </div>
                </div>
                <div class="blog-actions">
                    <a href="https://github.com/sreejith19931/Myusiq-App/releases/download/v{clean_version}/Myusiq-v{clean_version}.apk" class="btn btn-primary btn-sm">
                        ⚡ Download Myusiq-v{clean_version}.apk
                    </a>
                    <a href="https://github.com/sreejith19931/Myusiq-App/releases/tag/v{clean_version}" target="_blank" rel="noopener" class="btn btn-secondary btn-sm">
                        📦 View Release Notes on GitHub
                    </a>
                </div>
            </div>
        </div>
    </section>"""

    # Insert or replace blog post section above 'features' section
    blog_pattern = r'(<!-- Latest Release Blog Announcement -->[\s\S]*?<\/section>)'
    if re.search(blog_pattern, html):
        html = re.sub(blog_pattern, blog_post_html, html)
    else:
        features_pattern = r'(\s*<section id="features" class="features-section">)'
        html = re.sub(features_pattern, f'\n\n{blog_post_html}\n\n\\1', html)

    # 4. Handle Announcement Banner for Major Releases
    is_major = is_major_release(clean_version)
    if is_major:
        banner_display = "flex"
        banner_text = f"🎉 Major Release! Welcome to Myusiq v{clean_version} — Experience the next level of offline audio!"
    else:
        banner_display = "flex" if is_major else "none"
        banner_text = f"🎉 Major Release! Welcome to Myusiq v{clean_version}!"

    # Update banner display style and text
    banner_pattern = r'(<div id="announcement-banner" class="announcement-banner" style="display:\s*)[^;]+(;[^">]*">)'
    html = re.sub(banner_pattern, f'\\1{banner_display}\\2', html)

    banner_text_pattern = r'(<span id="banner-text">)[^<]*(</span>)'
    html = re.sub(banner_text_pattern, f'\\1{banner_text}\\2', html)

    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(html)

    print(f"Successfully updated docs/index.html to v{clean_version} (Is Major Release: {is_major})")

if __name__ == "__main__":
    update_website()
