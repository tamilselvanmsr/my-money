package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val balance: Double, // This is the current running/adjusted balance
    val type: String, // "CASH", "BANK", "CREDIT_CARD", "SAVINGS"
    val lastFour: String? = null // Linked last 4 digits for SMS matching (e.g., "1234")
)

