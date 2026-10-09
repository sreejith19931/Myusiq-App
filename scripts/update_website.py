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

def is_major_or_minor_release(version):
    """
    Returns True for major and minor releases (e.g., 1.1, 1.2, 1.3, 1.1.0, 1.2.0, 2.0.0).
    Returns False for patch/hotfix releases (e.g., 1.2.1, 1.2.2, 1.1.3).
    """
    version = str(version).strip().lstrip('v').lstrip('V')
    parts = version.split('.')
    if len(parts) == 2:
        return True
    elif len(parts) >= 3:
        try:
            return int(parts[2]) == 0
        except ValueError:
            return False
    return True

def get_blog_release_data(releases):
    """
    Finds the latest major/minor release to feature in the Blog Post.
    Aggregates changelog items from that release and any preceding patch releases
    since the prior major/minor release (e.g., all changes between 1.2 and 1.3).
    """
    if not releases:
        return None

    target_idx = None
    for idx, rel in enumerate(releases):
        if is_major_or_minor_release(rel['version']):
            target_idx = idx
            break

    if target_idx is None:
        target_idx = 0

    blog_release = releases[target_idx]
    aggregated_items = list(blog_release['items'])

    for idx in range(target_idx + 1, len(releases)):
        rel = releases[idx]
        if is_major_or_minor_release(rel['version']):
            break
        for item in rel['items']:
            if item not in aggregated_items:
                aggregated_items.append(item)

    return {
        "version": blog_release['version'],
        "date": blog_release['date'],
        "items": aggregated_items
    }

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

    # 1. Update version buttons and download links to latest APK (even if patch)
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

    # 2. Build Timeline HTML (all releases including patches)
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

    timeline_pattern = r'(<div class="timeline reveal">)[\s\S]*?(<\/div>\s*<\/div>\s*<\/section>)'
    replacement = f'\\1\n{timeline_html}\n            \\2'
    html = re.sub(timeline_pattern, replacement, html)

    # 3. Build Featured Blog Post Section (strictly for Major/Minor releases like 1.1, 1.2, 1.3)
    blog_data = get_blog_release_data(releases)
    if blog_data:
        blog_clean_version = blog_data['version'].lstrip('v')
        blog_items_html = "\n".join([f"                            <li>{markdown_to_html(it)}</li>" for it in blog_data['items']])
        blog_post_html = f"""    <!-- Latest Release Blog Announcement -->
    <section id="latest-blog-post" class="blog-announcement-section">
        <div class="container">
            <div class="glass-card blog-card reveal">
                <div class="blog-header">
                    <div class="blog-badge-wrap">
                        <span class="blog-badge">🚀 FEATURED RELEASE</span>
                        <span class="blog-version">v{blog_clean_version}</span>
                    </div>
                    <span class="blog-date">{blog_data['date']}</span>
                </div>
                <h2 class="blog-title">What's New in Myusiq v{blog_clean_version}</h2>
                <div class="blog-body">
                    <p class="blog-intro">We are excited to announce the official release of <strong>Myusiq v{blog_clean_version}</strong>! This update brings key feature additions, performance optimizations, and important usability upgrades to your audio experience.</p>
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

        blog_pattern = r'(<!-- Latest Release Blog Announcement -->[\s\S]*?<\/section>)'
        if re.search(blog_pattern, html):
            html = re.sub(blog_pattern, blog_post_html, html)
        else:
            features_pattern = r'(\s*<section id="features" class="features-section">)'
            html = re.sub(features_pattern, f'\n\n{blog_post_html}\n\n\\1', html)

    # 4. Handle Announcement Banner for Major/Minor Releases
    is_major_or_minor = is_major_or_minor_release(clean_version)
    banner_display = "flex" if is_major_or_minor else "none"
    banner_text = f"🎉 Major Release! Welcome to Myusiq v{clean_version} — Experience the next level of offline audio!"

    banner_pattern = r'(<div id="announcement-banner" class="announcement-banner" style="display:\s*)[^;]+(;[^">]*">)'
    html = re.sub(banner_pattern, f'\\1{banner_display}\\2', html)

    banner_text_pattern = r'(<span id="banner-text">)[^<]*(</span>)'
    html = re.sub(banner_text_pattern, f'\\1{banner_text}\\2', html)

    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(html)

    print(f"Successfully updated docs/index.html to v{clean_version} (Featured Blog Post: v{blog_data['version'] if blog_data else 'none'})")

if __name__ == "__main__":
    update_website()
