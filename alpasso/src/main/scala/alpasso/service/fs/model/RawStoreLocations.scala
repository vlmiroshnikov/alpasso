package alpasso.service.fs.model

import java.nio.file.Path

opaque type PayloadPath <: Path = Path
opaque type MetaPath <: Path    = Path

case class RawStoreLocations(secretData: Path, metadata: Path)
