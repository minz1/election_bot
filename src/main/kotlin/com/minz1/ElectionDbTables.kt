package com.minz1

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column

object Users : IntIdTable(columnName = "userid") {
    val discordId: Column<String> = varchar("discordid", length=32)
    val party: Column<Int> = integer("party")
}

class User(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<User>(Users)
    var discordId by Users.discordId
    var party by Users.party
}

object Nominations : IntIdTable(columnName = "nominationid") {
    val nominatorId = reference("nominatorid", Users)
    val nomineeId = reference("nomineeid", Users)
}

class Nomination(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<Nomination>(Nominations)
    var nominatorId by Nominations.nominatorId
    var nomineeId by Nominations.nomineeId
}

object Dropouts : IntIdTable(columnName = "dropoutid") {
    val dropoutUserId = reference("dropoutuserid", Users)
}

class Dropout(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<Dropout>(Dropouts)
    var dropoutUserId by Dropouts.dropoutUserId
}

object RunoffNominations : IntIdTable(columnName = "runoffnominationid") {
    val runoffNominatorId = reference("runoffnominatorid", Users)
    val runoffNomineeId = reference("runoffnomineeid", Users)
}

class RunoffNomination(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<RunoffNomination>(RunoffNominations)
    var runoffNominatorId by RunoffNominations.runoffNominatorId
    var runoffNomineeId by RunoffNominations.runoffNomineeId
}

object GeneralVotes : IntIdTable(columnName = "generalvoteid") {
    val voterId = reference("voterid", Users)
    val firstCandidateId = reference("firstcandidateid", Users)
    val secondCandidateId = reference("secondcandidateid", Users)
    val thirdCandidateId = reference("thirdcandidateid", Users)
}

class GeneralVote(id: EntityID<Int>): IntEntity(id) {
    companion object : IntEntityClass<GeneralVote>(GeneralVotes)
    var voterId by GeneralVotes.voterId
    var firstCandidateId by GeneralVotes.firstCandidateId
    var secondCandidateId by GeneralVotes.secondCandidateId
    var thirdCandidateId by GeneralVotes.thirdCandidateId
}