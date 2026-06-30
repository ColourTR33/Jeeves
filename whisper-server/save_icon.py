"""
Save the Jeeves app icon from a source image.
Usage: python save_icon.py <path_to_source_image>

If no argument given, uses the placeholder already in the icons folder.
Converts the source to proper ICO (multi-size) and PNG formats.
"""
import sys
from PIL import Image

def create_icons(source_path):
    img = Image.open(source_path).convert('RGBA')
    
    # Crop to square if not already
    w, h = img.size
    if w != h:
        size = min(w, h)
        left = (w - size) // 2
        top = (h - size) // 2
        img = img.crop((left, top, left + size, top + size))
    
    # Save PNG (256x256)
    png_path = r'C:\Repos\Jeeves\Jeeves\desktopApp\src\desktopMain\resources\icons\jeeves-icon.png'
    img_256 = img.resize((256, 256), Image.LANCZOS)
    img_256.save(png_path)
    print(f'PNG saved: {png_path}')
    
    # Save ICO with multiple sizes
    ico_path = r'C:\Repos\Jeeves\Jeeves\desktopApp\src\desktopMain\resources\icons\jeeves-icon.ico'
    img_256.save(ico_path, format='ICO', sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
    print(f'ICO saved: {ico_path}')

if __name__ == '__main__':
    if len(sys.argv) > 1:
        create_icons(sys.argv[1])
    else:
        print('Usage: python save_icon.py <path_to_image>')
        print('Example: python save_icon.py ~/Downloads/jeeves-icon.png')
        print()
        print('To use: save the Jeeves icon image, then run this script with the path.')
