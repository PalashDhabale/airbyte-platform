
import io.airbyte.api.client.AirbyteApiClient
import io.airbyte.api.client.generated.ActorDefinitionVersionApi
import io.airbyte.api.client.model.generated.ActorDefinitionVersionRead
import io.airbyte.api.client.model.generated.SupportState
import io.airbyte.connector.rollout.shared.models.ConnectorRolloutActivityInputVerifyDefaultVersion
import io.airbyte.connector.rollout.worker.activities.VerifyDefaultVersionActivityImpl
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class VerifyDefaultVersionActivityImplTest {
  private lateinit var airbyteApiClient: AirbyteApiClient
  private lateinit var actorDefinitionVersionApi: ActorDefinitionVersionApi
  private lateinit var verifyDefaultVersionActivity: VerifyDefaultVersionActivityImpl

  companion object {
    private const val DOCKER_REPOSITORY = "airbyte/source-faker"
    private const val DOCKER_IMAGE_TAG = "0.1"
    private val ACTOR_DEFINITION_ID = UUID.randomUUID()
    private val ROLLOUT_ID = UUID.randomUUID()
  }

  @BeforeEach
  fun setUp() {
    airbyteApiClient = mockk<AirbyteApiClient>()
    actorDefinitionVersionApi = mockk<ActorDefinitionVersionApi>()
    every { airbyteApiClient.actorDefinitionVersionApi } returns actorDefinitionVersionApi

    val realActivity = VerifyDefaultVersionActivityImpl(airbyteApiClient)
    verifyDefaultVersionActivity = spyk(realActivity)

    every { verifyDefaultVersionActivity.heartbeatAndSleep(any()) } just Runs
  }

  @Test
  fun `test verifyDefaultVersion`() {
    // Mock the ActorDefinitionVersionApi to return the response dynamically
    every {
      actorDefinitionVersionApi.getActorDefinitionVersionDefault(any())
    } returnsMany
      listOf(
        ActorDefinitionVersionRead(
          // Initial incorrect tag
          dockerImageTag = "0.1",
          dockerRepository = DOCKER_REPOSITORY,
          isVersionOverrideApplied = true,
          supportState = SupportState.SUPPORTED,
          supportsRefreshes = true,
          supportsFileTransfer = false,
        ),
        ActorDefinitionVersionRead(
          // Correct tag for subsequent verification
          dockerImageTag = DOCKER_IMAGE_TAG,
          dockerRepository = DOCKER_REPOSITORY,
          isVersionOverrideApplied = true,
          supportState = SupportState.SUPPORTED,
          supportsRefreshes = true,
          supportsFileTransfer = false,
        ),
      )

    // Test without "-rc" suffix in the input dockerImageTag
    val input =
      ConnectorRolloutActivityInputVerifyDefaultVersion(
        dockerRepository = DOCKER_REPOSITORY,
        dockerImageTag = DOCKER_IMAGE_TAG,
        actorDefinitionId = ACTOR_DEFINITION_ID,
        rolloutId = ROLLOUT_ID,
        // 1 second limit
        limit = 1000,
        // Poll every half second
        timeBetweenPolls = 500,
      )

    verifyDefaultVersionActivity.verifyDefaultVersion(input)

    verify { actorDefinitionVersionApi.getActorDefinitionVersionDefault(any()) }

    // Test with "-rc" suffix in the input dockerImageTag
    val inputWithRcSuffix =
      ConnectorRolloutActivityInputVerifyDefaultVersion(
        dockerRepository = DOCKER_REPOSITORY,
        dockerImageTag = "$DOCKER_IMAGE_TAG-rc.1",
        actorDefinitionId = ACTOR_DEFINITION_ID,
        rolloutId = ROLLOUT_ID,
      )

    verifyDefaultVersionActivity.verifyDefaultVersion(inputWithRcSuffix)

    verify(exactly = 2) { actorDefinitionVersionApi.getActorDefinitionVersionDefault(any()) }
  }

  @Test
  fun `test verifyDefaultVersion throws exception on timeout`() {
    // Simulate the scenario where the response always returns a different tag, causing the retry to continue
    every {
      actorDefinitionVersionApi.getActorDefinitionVersionDefault(any())
    } returns
      ActorDefinitionVersionRead(
        // Different tag that will cause a timeout
        dockerImageTag = "0.2",
        dockerRepository = DOCKER_REPOSITORY,
        isVersionOverrideApplied = true,
        supportState = SupportState.SUPPORTED,
        supportsRefreshes = true,
        supportsFileTransfer = false,
      )

    val input =
      ConnectorRolloutActivityInputVerifyDefaultVersion(
        dockerRepository = DOCKER_REPOSITORY,
        dockerImageTag = DOCKER_IMAGE_TAG,
        actorDefinitionId = ACTOR_DEFINITION_ID,
        rolloutId = ROLLOUT_ID,
        // 1 second limit
        limit = 1000,
        // Poll every half second
        timeBetweenPolls = 500,
      )

    // Use assertThrows to verify that the exception is thrown due to timeout
    val exception =
      assertThrows<IllegalStateException> {
        verifyDefaultVersionActivity.verifyDefaultVersion(input)
      }

    verify(atLeast = 1) { actorDefinitionVersionApi.getActorDefinitionVersionDefault(any()) }
    assert(exception.message!!.contains("Timed out"))
  }
}
