package io.airbyte.data.services.impls.data

import io.airbyte.config.ScopeType
import io.airbyte.data.exceptions.ConfigNotFoundException
import io.airbyte.data.repositories.PermissionRepository
import io.airbyte.data.repositories.UserInvitationRepository
import io.airbyte.data.repositories.entities.Permission
import io.airbyte.data.repositories.entities.UserInvitation
import io.airbyte.data.services.impls.data.mappers.EntityInvitationStatus
import io.airbyte.data.services.impls.data.mappers.EntityPermissionType
import io.airbyte.data.services.impls.data.mappers.EntityScopeType
import io.airbyte.data.services.impls.data.mappers.toConfigModel
import io.mockk.clearAllMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

internal class UserInvitationServiceDataImplTest {
  private val userInvitationRepository = mockk<UserInvitationRepository>()
  private val permissionRepository = mockk<PermissionRepository>()
  private val userInvitationService = UserInvitationServiceDataImpl(userInvitationRepository, permissionRepository)

  private val invitation =
    UserInvitation(
      id = UUID.randomUUID(),
      inviteCode = "some-code",
      inviterUserId = UUID.randomUUID(),
      invitedEmail = "invited@airbyte.io",
      scopeId = UUID.randomUUID(),
      scopeType = EntityScopeType.workspace,
      permissionType = EntityPermissionType.workspace_admin,
      status = EntityInvitationStatus.pending,
      createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
      updatedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
    )

  @BeforeEach
  fun reset() {
    clearAllMocks()
  }

  @Test
  fun `test get user invitation by invite code`() {
    every { userInvitationRepository.findByInviteCode(invitation.inviteCode) } returns Optional.of(invitation)

    val invitation = userInvitationService.getUserInvitationByInviteCode(invitation.inviteCode)
    assert(invitation == this.invitation.toConfigModel())

    verify { userInvitationRepository.findByInviteCode(this@UserInvitationServiceDataImplTest.invitation.inviteCode) }
  }

  @Test
  fun `test get user invitation by non-existent code throws`() {
    every { userInvitationRepository.findByInviteCode(any()) } returns Optional.empty()

    assertThrows<ConfigNotFoundException> { userInvitationService.getUserInvitationByInviteCode("non-existent-code") }

    verify { userInvitationRepository.findByInviteCode("non-existent-code") }
  }

  @Test
  fun `test create user invitation`() {
    every { userInvitationRepository.save(invitation) } returns invitation

    val result = userInvitationService.createUserInvitation(invitation.toConfigModel())
    assert(result == invitation.toConfigModel())

    verify { userInvitationRepository.save(invitation) }
  }

  @Test
  fun `test accept user invitation`() {
    val invitedUserId = UUID.randomUUID()
    val expectedUpdatedInvitation = invitation.copy(status = EntityInvitationStatus.accepted)

    every { userInvitationRepository.findByInviteCode(invitation.inviteCode) } returns Optional.of(invitation)
    every { userInvitationRepository.update(expectedUpdatedInvitation) } returns expectedUpdatedInvitation
    every { permissionRepository.save(any()) } returns mockk()

    userInvitationService.acceptUserInvitation(invitation.inviteCode, invitedUserId)

    // verify the expected permission is created for the invited user
    verify {
      permissionRepository.save(
        match<Permission> {
          it.userId == invitedUserId &&
            it.permissionType == invitation.permissionType &&
            it.workspaceId == invitation.scopeId &&
            it.organizationId == null
        },
      )
    }

    // verify the invitation status is updated to accepted
    verify { userInvitationRepository.update(expectedUpdatedInvitation) }
  }

  @ParameterizedTest
  @EnumSource(value = EntityInvitationStatus::class)
  fun `test accept user invitation fails if not pending`(status: EntityInvitationStatus) {
    if (status == EntityInvitationStatus.pending) {
      return // not testing this case
    }

    val invitedUserId = UUID.randomUUID()
    val invitation = this.invitation.copy(status = status)

    every { userInvitationRepository.findByInviteCode(invitation.inviteCode) } returns Optional.of(invitation)

    assertThrows<IllegalStateException> { userInvitationService.acceptUserInvitation(invitation.inviteCode, invitedUserId) }

    verify(exactly = 0) { userInvitationRepository.update(any()) }
  }

  @Test
  fun `test get pending invitations`() {
    val workspaceId = UUID.randomUUID()
    val organizationId = UUID.randomUUID()
    val mockWorkspaceInvitations = listOf(invitation, invitation.copy(id = UUID.randomUUID()))
    val mockOrganizationInvitations = listOf(invitation.copy(id = UUID.randomUUID()), invitation.copy(id = UUID.randomUUID()))

    every {
      userInvitationRepository.findByStatusAndScopeTypeAndScopeId(EntityInvitationStatus.pending, EntityScopeType.workspace, workspaceId)
    } returns mockWorkspaceInvitations
    every {
      userInvitationRepository.findByStatusAndScopeTypeAndScopeId(EntityInvitationStatus.pending, EntityScopeType.organization, organizationId)
    } returns mockOrganizationInvitations

    val workspaceResult = userInvitationService.getPendingInvitations(ScopeType.WORKSPACE, workspaceId)
    val organizationResult = userInvitationService.getPendingInvitations(ScopeType.ORGANIZATION, organizationId)

    verify(exactly = 1) {
      userInvitationRepository.findByStatusAndScopeTypeAndScopeId(EntityInvitationStatus.pending, EntityScopeType.workspace, workspaceId)
    }
    verify(exactly = 1) {
      userInvitationRepository.findByStatusAndScopeTypeAndScopeId(EntityInvitationStatus.pending, EntityScopeType.organization, organizationId)
    }
    confirmVerified(userInvitationRepository)

    assert(workspaceResult == mockWorkspaceInvitations.map { it.toConfigModel() })
    assert(organizationResult == mockOrganizationInvitations.map { it.toConfigModel() })
  }
}
