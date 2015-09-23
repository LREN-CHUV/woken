package core

// Messages

trait RestMessage

//case class GetPetsWithOwners(petNames: List[String]) extends RestMessage
//case class PetsWithOwners(pets: Seq[EnrichedPet]) extends RestMessage

// Domain objects

object Ok

case class Error(message: String)

case class Validation(message: String)

// Exceptions

case object ChronosNotReachableException extends Exception("Cannot connect to Chronos")
