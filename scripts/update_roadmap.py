import os
import sys

# Alias to update_website.py
current_dir = os.path.dirname(os.path.abspath(__file__))
update_website_path = os.path.join(current_dir, "update_website.py")

if os.path.exists(update_website_path):
    exec(open(update_website_path, encoding="utf-8").read())
