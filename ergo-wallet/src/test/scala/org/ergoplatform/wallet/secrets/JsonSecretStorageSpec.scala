package org.ergoplatform.wallet.secrets

import org.ergoplatform.wallet.settings.SecretStorageSettings
import org.ergoplatform.wallet.utils.{Generators, FileUtils}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class JsonSecretStorageSpec
  extends AnyPropSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with Generators
    with FileUtils {

  property("initialization and unlock") {
    forAll(entropyGen, passwordGen, encryptionSettingsGen) { (seed, pass, cryptoSettings) =>
      val dir = createTempDir
      val settings = SecretStorageSettings(dir.getAbsolutePath, cryptoSettings)
      val storage = JsonSecretStorage.init(seed, pass)(settings)

      storage.isLocked shouldBe true

      val unlockTry = storage.unlock(pass)

      unlockTry shouldBe 'success

      storage.isLocked shouldBe false
    }
  }

  property("secrets erasure on lock") {
    forAll(entropyGen, passwordGen, encryptionSettingsGen) { (seed, pass, cryptoSettings) =>
      val dir = createTempDir
      val settings = SecretStorageSettings(dir.getAbsolutePath, cryptoSettings)
      val storage = JsonSecretStorage.init(seed, pass)(settings)

      storage.unlock(pass)

      val secret = storage.secret

      secret.nonEmpty shouldBe true

      storage.lock()

      secret.forall(_.isErased) shouldBe true

      storage.secret.isEmpty shouldBe true
    }
  }

}
