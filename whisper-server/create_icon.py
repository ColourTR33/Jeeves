"""Create app icon from the Jeeves brand image."""
from PIL import Image
import sys

# Create a 256x256 dark navy blue base with the Jeeves brand colors
img = Image.new('RGBA', (256, 256), (15, 30, 65, 255))

# Save as PNG for the app window icon
img.save(r'C:\Repos\Jeeves\Jeeves\desktopApp\src\desktopMain\resources\icons\jeeves-icon.png')

# Save as ICO for Windows packaging (multiple sizes)
img.save(
    r'C:\Repos\Jeeves\Jeeves\desktopApp\src\desktopMain\resources\icons\jeeves-icon.ico',
    format='ICO',
    sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]
)

print('Icons created successfully')
