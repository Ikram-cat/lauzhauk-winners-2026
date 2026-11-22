package com.alt.backend.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---- accounts (/api/auth) ----

@Serializable
data class PlaceRefDto(val input: String? = null, val name: String? = null, val id: String? = null)

@Serializable
data class UserDto(val id: String, val email: String, val displayName: String? = null)

@Serializable
data class AuthResponseDto(val status: String, val user: UserDto, val token: String, val expiresAt: String)

@Serializable
data class MeResponseDto(val status: String, val user: UserDto)

@Serializable
data class RegisterRequestDto(val email: String? = null, val password: String? = null, val displayName: String? = null)

@Serializable
data class LoginRequestDto(val email: String? = null, val password: String? = null)

// ---- saved plans (/api/plans) ----

@Serializable
data class PlanCreateRequestDto(
    val origin: PlaceRefDto? = null, val destination: PlaceRefDto? = null,
    val departure: String? = null, val label: String? = null, val journey: JsonElement? = null
)

@Serializable
data class PlanDto(
    val id: String, val label: String? = null, val origin: PlaceRefDto, val destination: PlaceRefDto,
    val departure: String, val journey: JsonElement? = null, val createdAt: String
)

@Serializable
data class PlansResponseDto(val plans: List<PlanDto>)

// ---- past travels (/api/travels) ----

@Serializable
data class TravelCreateRequestDto(
    val planId: String? = null, val origin: PlaceRefDto? = null, val destination: PlaceRefDto? = null,
    val departure: String? = null, val arrival: String? = null, val rating: Int? = null,
    val note: String? = null, val delayMinutes: Int? = null
)

@Serializable
data class TravelDto(
    val id: String, val planId: String? = null, val origin: PlaceRefDto, val destination: PlaceRefDto,
    val departure: String, val arrival: String? = null, val rating: Int? = null,
    val note: String? = null, val delayMinutes: Int? = null, val createdAt: String
)

@Serializable
data class TravelsResponseDto(val travels: List<TravelDto>)

@Serializable
data class StatusResponseDto(val status: String)
