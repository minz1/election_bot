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