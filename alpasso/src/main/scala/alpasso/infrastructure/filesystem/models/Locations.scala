package alpasso.infrastructure.filesystem.models

import java.nio.file.Path

opaque type PayloadPath <: Path = Path
opaque type MetaPath <: Path    = Path

case class Locations(secretData: Path, metadata: Path)
