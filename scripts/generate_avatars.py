import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

def create_chat_group_avatar():
    size = 1024
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    # 1. Background radial gradient
    bg = Image.new("RGBA", (size, size), (8, 18, 37, 255))
    draw_bg = ImageDraw.Draw(bg)

    cx, cy = size // 2, size // 2
    max_r = math.sqrt(cx**2 + cy**2)

    # Draw radial glow
    for r in range(int(max_r), 0, -8):
        factor = 1.0 - (r / max_r)
        red = int(8 + (25 - 8) * factor)
        green = int(18 + (80 - 18) * factor)
        blue = int(37 + (160 - 37) * factor)
        draw_bg.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(red, green, blue, 255))

    img = Image.alpha_composite(img, bg)

    # 2. Outer decorative ring / glow
    ring_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ring_draw = ImageDraw.Draw(ring_img)

    # Outer accent circle (1024x1024 canvas, 440 radius circle)
    ring_draw.ellipse([cx - 440, cy - 440, cx + 440, cy + 440], outline=(0, 229, 255, 60), width=6)
    ring_draw.ellipse([cx - 425, cy - 425, cx + 425, cy + 425], outline=(0, 168, 255, 120), width=12)

    # Glow layer
    ring_blur = ring_img.filter(ImageFilter.GaussianBlur(15))
    img = Image.alpha_composite(img, ring_blur)
    img = Image.alpha_composite(img, ring_img)

    # 3. Headphones Icon
    head_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    head_draw = ImageDraw.Draw(head_img)

    # Headband arc
    headband_box = [cx - 220, cy - 260, cx + 220, cy + 180]
    head_draw.arc(headband_box, start=180, end=360, fill=(230, 245, 255, 255), width=28)

    # Earcups - Left and Right
    # Left Earcup Chassis
    head_draw.rounded_rectangle([cx - 260, cy - 30, cx - 180, cy + 170], radius=35, fill=(0, 168, 255, 255))
    head_draw.rounded_rectangle([cx - 240, cy - 10, cx - 185, cy + 150], radius=20, fill=(10, 30, 60, 255))

    # Right Earcup Chassis
    head_draw.rounded_rectangle([cx + 180, cy - 30, cx + 260, cy + 170], radius=35, fill=(0, 168, 255, 255))
    head_draw.rounded_rectangle([cx + 185, cy - 10, cx + 240, cy + 150], radius=20, fill=(10, 30, 60, 255))

    # Equalizer Waveform in center
    wave_x_start = cx - 140
    wave_x_end = cx + 140
    bar_width = 16
    spacing = 10

    heights = [40, 80, 130, 190, 240, 170, 220, 260, 180, 120, 70, 40]
    num_bars = len(heights)
    total_w = num_bars * bar_width + (num_bars - 1) * spacing
    start_x = cx - total_w // 2

    for i, h in enumerate(heights):
        bx = start_x + i * (bar_width + spacing)
        by_top = cy + 60 - h // 2
        by_bottom = cy + 60 + h // 2

        # Color gradient per bar
        color = (0, 242, 254, 255) if i % 2 == 0 else (79, 172, 254, 255)
        head_draw.rounded_rectangle([bx, by_top, bx + bar_width, by_bottom], radius=8, fill=color)

    # Glow effect for headphones & waveform
    head_glow = head_img.filter(ImageFilter.GaussianBlur(20))
    img = Image.alpha_composite(img, head_glow)
    img = Image.alpha_composite(img, head_img)

    # 4. Text Banner - MYUSIQ COMMUNITY
    text_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    text_draw = ImageDraw.Draw(text_img)

    # Bottom pill badge
    badge_w, badge_h = 580, 110
    badge_x = cx - badge_w // 2
    badge_y = cy + 280
    text_draw.rounded_rectangle([badge_x, badge_y, badge_x + badge_w, badge_y + badge_h], radius=55, fill=(0, 168, 255, 230))
    text_draw.rounded_rectangle([badge_x + 4, badge_y + 4, badge_x + badge_w - 4, badge_y + badge_h - 4], radius=51, outline=(255, 255, 255, 180), width=4)

    # Try loading bold truetype font or fall back
    try:
        font = ImageFont.truetype("arial.ttf", 52)
        font_sub = ImageFont.truetype("arialbd.ttf", 46)
    except Exception:
        font = ImageFont.load_default()
        font_sub = font

    # Draw Text
    text_main = "MYUSIQ COMMUNITY"

    bbox = text_draw.textbbox((0, 0), text_main, font=font_sub)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]

    text_draw.text((cx - tw // 2, badge_y + (badge_h - th) // 2 - 4), text_main, fill=(255, 255, 255, 255), font=font_sub)

    img = Image.alpha_composite(img, text_img)
    return img


def create_updates_channel_avatar():
    size = 1024
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    # 1. Background gradient (Sleek deep navy to cobalt)
    bg = Image.new("RGBA", (size, size), (4, 12, 26, 255))
    draw_bg = ImageDraw.Draw(bg)

    cx, cy = size // 2, size // 2
    max_r = math.sqrt(cx**2 + cy**2)

    for r in range(int(max_r), 0, -8):
        factor = 1.0 - (r / max_r)
        red = int(4 + (15 - 4) * factor)
        green = int(12 + (40 - 12) * factor)
        blue = int(26 + (90 - 26) * factor)
        draw_bg.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(red, green, blue, 255))

    img = Image.alpha_composite(img, bg)

    # 2. Hexagon / Broadcast Halo Outer Ring
    ring_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ring_draw = ImageDraw.Draw(ring_img)

    # Circular broadcast waves
    for r_wave in [380, 410, 440]:
        ring_draw.ellipse([cx - r_wave, cy - r_wave, cx + r_wave, cy + r_wave], outline=(0, 229, 255, 70), width=4)

    # Glowing top accent arcs
    ring_draw.arc([cx - 440, cy - 440, cx + 440, cy + 440], start=220, end=320, fill=(255, 215, 0, 255), width=12)
    ring_draw.arc([cx - 440, cy - 440, cx + 440, cy + 440], start=40, end=140, fill=(0, 229, 255, 255), width=12)

    ring_blur = ring_img.filter(ImageFilter.GaussianBlur(12))
    img = Image.alpha_composite(img, ring_blur)
    img = Image.alpha_composite(img, ring_img)

    # 3. Headphones + Broadcast Speaker Icon
    icon_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    icon_draw = ImageDraw.Draw(icon_img)

    # Headband
    headband_box = [cx - 200, cy - 270, cx + 200, cy + 150]
    icon_draw.arc(headband_box, start=180, end=360, fill=(255, 255, 255, 255), width=26)

    # Earcups - Sleek Chrome Gold & Cyan
    icon_draw.rounded_rectangle([cx - 240, cy - 40, cx - 170, cy + 150], radius=30, fill=(255, 215, 0, 255))
    icon_draw.rounded_rectangle([cx - 225, cy - 20, cx - 175, cy + 130], radius=20, fill=(15, 25, 45, 255))

    icon_draw.rounded_rectangle([cx + 170, cy - 40, cx + 240, cy + 150], radius=30, fill=(255, 215, 0, 255))
    icon_draw.rounded_rectangle([cx + 175, cy - 20, cx + 225, cy + 130], radius=20, fill=(15, 25, 45, 255))

    # Center Megaphone / Broadcast Waves Icon
    # Radio tower / Sound wave lines
    for radius, width_a in [(60, 8), (110, 10), (160, 12)]:
        icon_draw.arc([cx - radius, cy + 20 - radius, cx + radius, cy + 20 + radius], start=210, end=330, fill=(0, 229, 255, 255), width=width_a)
        icon_draw.arc([cx - radius, cy + 20 - radius, cx + radius, cy + 20 + radius], start=30, end=150, fill=(0, 229, 255, 255), width=width_a)

    # Center pulsing node
    icon_draw.ellipse([cx - 24, cy + 20 - 24, cx + 24, cy + 20 + 24], fill=(255, 215, 0, 255))

    icon_glow = icon_img.filter(ImageFilter.GaussianBlur(18))
    img = Image.alpha_composite(img, icon_glow)
    img = Image.alpha_composite(img, icon_img)

    # 4. Text Banner - OFFICIAL UPDATES
    text_img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    text_draw = ImageDraw.Draw(text_img)

    # Bottom pill badge - Dark Gold & Cyan
    badge_w, badge_h = 560, 110
    badge_x = cx - badge_w // 2
    badge_y = cy + 280
    text_draw.rounded_rectangle([badge_x, badge_y, badge_x + badge_w, badge_y + badge_h], radius=55, fill=(15, 35, 65, 240))
    text_draw.rounded_rectangle([badge_x, badge_y, badge_x + badge_w, badge_y + badge_h], radius=55, outline=(255, 215, 0, 255), width=5)

    try:
        font_sub = ImageFont.truetype("arialbd.ttf", 46)
    except Exception:
        font_sub = ImageFont.load_default()

    text_main = "MYUSIQ UPDATES"

    bbox = text_draw.textbbox((0, 0), text_main, font=font_sub)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]

    text_draw.text((cx - tw // 2, badge_y + (badge_h - th) // 2 - 4), text_main, fill=(255, 215, 0, 255), font=font_sub)

    img = Image.alpha_composite(img, text_img)
    return img


def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    output_dir = os.path.join(root_dir, "images")
    os.makedirs(output_dir, exist_ok=True)

    # Artifacts directory for IDE viewing
    artifacts_dir = r"C:\Users\Sreejith\AppData\Local\Google\AndroidStudio2026.2.1\projects\mysimplemusicplayer.e670f2ba\.artifacts\2f70d6a7-4f09-48bc-8781-0ebf4a4cccb8\images"
    os.makedirs(artifacts_dir, exist_ok=True)

    chat_avatar = create_chat_group_avatar()
    chat_path = os.path.join(output_dir, "myusiq_chat_group_avatar.png")
    chat_artifact_path = os.path.join(artifacts_dir, "myusiq_chat_group_avatar.png")
    chat_avatar.save(chat_path, "PNG")
    chat_avatar.save(chat_artifact_path, "PNG")
    print(f"Chat Group Avatar saved to: {chat_path}")

    updates_avatar = create_updates_channel_avatar()
    updates_path = os.path.join(output_dir, "myusiq_updates_channel_avatar.png")
    updates_artifact_path = os.path.join(artifacts_dir, "myusiq_updates_channel_avatar.png")
    updates_avatar.save(updates_path, "PNG")
    updates_avatar.save(updates_artifact_path, "PNG")
    print(f"Updates Channel Avatar saved to: {updates_path}")

if __name__ == '__main__':
    main()
