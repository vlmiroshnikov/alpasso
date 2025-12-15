package alpasso.domain

enum SecretFilter:
  case Grep(pattern: String)
  case Empty
