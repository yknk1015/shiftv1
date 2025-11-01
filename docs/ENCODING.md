Encoding Policy

- All HTML templates under src/main/resources/templates use UTF-8 (no BOM).
- Server response encoding is forced to UTF-8 via application.properties:
  - server.servlet.encoding.charset=UTF-8
  - server.servlet.encoding.enabled=true
  - server.servlet.encoding.force=true
  - spring.thymeleaf.encoding=UTF-8

Contributor Rules

- When adding or editing templates, ensure your editor saves files as UTF-8.
- Avoid mixing encodings or pasting text from unknown encodings.
- If you notice mojibake (garbled text), retype the text in UTF-8 or replace it from a known UTF-8 source.

