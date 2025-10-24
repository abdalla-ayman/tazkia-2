#!/bin/bash
# combine_code.sh

OUTPUT_FILE="combined_code.txt"
> "$OUTPUT_FILE"  # Clear file if it exists

echo "Combining code files into $OUTPUT_FILE..."

# Define target directories
DIRS=(
  "app/src/main/java/com/tazkia/ai/blurfilter/ml"
  "app/src/main/java/com/tazkia/ai/blurfilter/service"
  "app/src/main/java/com/tazkia/ai/blurfilter/ui"
  "app/src/main/java/com/tazkia/ai/blurfilter/utils"
  "app/src/main/res/layout"
  "app/src/main/res/xml"
)

# Loop through directories and append code
for dir in "${DIRS[@]}"; do
  if [ -d "$dir" ]; then
    echo -e "\n\n=== DIRECTORY: $dir ===\n\n" >> "$OUTPUT_FILE"
    find "$dir" -type f \( -name "*.java" -o -name "*.kt" -o -name "*.xml" \) | while read file; do
      echo -e "\n\n--- FILE: $file ---\n\n" >> "$OUTPUT_FILE"
      cat "$file" >> "$OUTPUT_FILE"
      echo -e "\n\n" >> "$OUTPUT_FILE"
    done
  fi
done

# Add AndroidManifest.xml
MANIFEST="app/src/main/AndroidManifest.xml"
if [ -f "$MANIFEST" ]; then
  echo -e "\n\n--- FILE: $MANIFEST ---\n\n" >> "$OUTPUT_FILE"
  cat "$MANIFEST" >> "$OUTPUT_FILE"
fi

echo "✅ Done! All code combined in $OUTPUT_FILE"
