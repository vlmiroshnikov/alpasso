package pass.service.fs.model

import java.nio.file.Path

case class RawStoreEntry(secret: Path, meta: Path)
