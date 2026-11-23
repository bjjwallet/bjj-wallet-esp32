package pub.airlock

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pub.airlock.domain.*

// Public-only fixtures from Wallet Core 4.4.4, independently generated for indices 0/1/2/17/1024.
// Standard corresponds to BIP-84 published vectors; hidden uses the same public test mnemonic
// with the BIP-39 reference passphrase. Neither secrets nor key generation are included here.
@Serializable
internal data class PublicVector(
    val coin: String,
    val hardwareCoin: String,
    val symbol: String,
    val decimals: Int,
    val curve: String,
    val path: String,
    val xpub: String,
    val addresses: List<PublicAddress>,
) {
    fun account(mode: WalletMode = WalletMode.STANDARD): Account {
        val c = CoinCatalog.get(coin)
        return Account(
            "${mode.name}:$coin",
            coin,
            c.name,
            symbol,
            c.icon,
            mode,
            xpub,
            path,
            cachedAddresses = if (curve == "ed25519") addresses else emptyList(),
        )
    }
}

internal object PublicWalletFixtures {
    val standard: List<PublicVector> by lazy { Json.decodeFromString(standardJson) }
    val hidden: List<PublicVector> by lazy { Json.decodeFromString(hiddenJson) }

    fun response(mode: WalletMode, allAddresses: Boolean = true): JsonArray =
        JsonArray(
            (if (mode == WalletMode.HIDDEN) hidden else standard).flatMap { v ->
                (if (v.curve == "ed25519" && allAddresses) v.addresses else v.addresses.take(1))
                    .map { a ->
                        buildJsonObject {
                            put("coin", v.hardwareCoin)
                            put("symbol", v.symbol)
                            put("name", v.coin)
                            put("decimals", v.decimals)
                            put("curve", v.curve)
                            put("path", a.path)
                            put("address", a.address)
                            put("xpub", v.xpub)
                            put("is_main", if (mode == WalletMode.HIDDEN) 1 else 0)
                            if (v.curve == "ed25519") put("address_index", a.index)
                        }
                    }
            }
        )

    private val standardJson =
        """
[
  {
    "coin": "bitcoin",
    "hardwareCoin": "bitcoin",
    "symbol": "BTC",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/84'/0'/0'/0/0",
    "xpub": "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
    "addresses": [
      {
        "index": 0,
        "path": "m/84'/0'/0'/0/0",
        "address": "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
      },
      {
        "index": 1,
        "path": "m/84'/0'/0'/0/1",
        "address": "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
      },
      {
        "index": 2,
        "path": "m/84'/0'/0'/0/2",
        "address": "bc1qp59yckz4ae5c4efgw2s5wfyvrz0ala7rgvuz8z"
      },
      {
        "index": 17,
        "path": "m/84'/0'/0'/0/17",
        "address": "bc1qrz46a4gt0sghvvyt4gy5kp2rswmhtufv6sdq9v"
      },
      {
        "index": 1024,
        "path": "m/84'/0'/0'/0/1024",
        "address": "bc1qe7zr7rz0xwg3s4runkd8nzw8gecfzch8j8xhv5"
      }
    ]
  },
  {
    "coin": "ethereum",
    "hardwareCoin": "ethereum",
    "symbol": "ETH",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x516A2191b53f7654654F209CcA9668b16f149988"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xA3686634A71fbf2366Be4E5a37552Fe99A9fb4b4"
      }
    ]
  },
  {
    "coin": "tron",
    "hardwareCoin": "tron",
    "symbol": "TRX",
    "decimals": 6,
    "curve": "secp256k1",
    "path": "m/44'/195'/0'/0/0",
    "xpub": "xpub6D1AabNHCupeiLM65ZR9UStMhJ1vCpyV4XbZdyhMZBiJXALQtmn9p42VTQckoHVn8WNqS7dqnJokZHAHcHGoaQgmv8D45oNUKx6DZMNZBCd",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/195'/0'/0/0",
        "address": "TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH"
      },
      {
        "index": 1,
        "path": "m/44'/195'/0'/0/1",
        "address": "TSeJkUh4Qv67VNFwY8LaAxERygNdy6NQZK"
      },
      {
        "index": 2,
        "path": "m/44'/195'/0'/0/2",
        "address": "TYJPRrdB5APNeRs4R7fYZSwW3TcrTKw2gx"
      },
      {
        "index": 17,
        "path": "m/44'/195'/0'/0/17",
        "address": "TRmAfkAGBz7h9xS28VbnTBvTVrKC9iYNMb"
      },
      {
        "index": 1024,
        "path": "m/44'/195'/0'/0/1024",
        "address": "TSnUd4xtc6f2jhwdKdArSSscxLaLjUEuEz"
      }
    ]
  },
  {
    "coin": "bsc",
    "hardwareCoin": "smartchain",
    "symbol": "BNB",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x516A2191b53f7654654F209CcA9668b16f149988"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xA3686634A71fbf2366Be4E5a37552Fe99A9fb4b4"
      }
    ]
  },
  {
    "coin": "ripple",
    "hardwareCoin": "ripple",
    "symbol": "XRP",
    "decimals": 6,
    "curve": "secp256k1",
    "path": "m/44'/144'/0'/0/0",
    "xpub": "xpub6CFKyZTfzj3cyeRLUDKwQQ5s1tqTTdVgywKMVkrB2i1taGFbhazkxDzWVsfBHZpv7rg6qpDBGYR5oA8iazEfa44CdQkkknPFHJ7YCzncCS9",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/144'/0'/0/0",
        "address": "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
      },
      {
        "index": 1,
        "path": "m/44'/144'/0'/0/1",
        "address": "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17"
      },
      {
        "index": 2,
        "path": "m/44'/144'/0'/0/2",
        "address": "r4Sh61HP7nxB6mQxXSSeN2DCkG3sTrzb2c"
      },
      {
        "index": 17,
        "path": "m/44'/144'/0'/0/17",
        "address": "r4KhRMKKspctVE9PXbRcVeMAe6nQ4neJfi"
      },
      {
        "index": 1024,
        "path": "m/44'/144'/0'/0/1024",
        "address": "rw9hdqh9SAPxaThYRHjsvSXWnLBNaPYN3p"
      }
    ]
  },
  {
    "coin": "dogecoin",
    "hardwareCoin": "doge",
    "symbol": "DOGE",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/3'/0'/0/0",
    "xpub": "dgub8rUhDtD3YFGZTUphBfpBbzvFxSMKQXYLzg87Me2ta78r2SdVLmypBUkkxrrn9RTnchsyiJSkHZyLWxD13ibBiXtuFWktBoDaGaZjQUBLNLs",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/3'/0'/0/0",
        "address": "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC"
      },
      {
        "index": 1,
        "path": "m/44'/3'/0'/0/1",
        "address": "DAcDAtJRztxBHyA6D6h8du1HguyTR43Mas"
      },
      {
        "index": 2,
        "path": "m/44'/3'/0'/0/2",
        "address": "D8K3KyDQ9FXeC3ADCuWW7cnWC7RvjHjV8H"
      },
      {
        "index": 17,
        "path": "m/44'/3'/0'/0/17",
        "address": "DKnWrC1ue4Ys16Mgr19pPaivBn7cnhxmEu"
      },
      {
        "index": 1024,
        "path": "m/44'/3'/0'/0/1024",
        "address": "DPT2taJb8rff28B6Fp6sPCsKWembz2Vuuj"
      }
    ]
  },
  {
    "coin": "solana",
    "hardwareCoin": "solana",
    "symbol": "SOL",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/501'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/501'/0'",
        "address": "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL"
      },
      {
        "index": 1,
        "path": "m/44'/501'/1'",
        "address": "ANf3TEKFL6jPWjzkndo4CbnNdUNkBk4KHPggJs2nu8Xi"
      },
      {
        "index": 2,
        "path": "m/44'/501'/2'",
        "address": "Ag74i82rUZBTgMGLacCA1ZLnotvAca8CLscXcrG6Nwem"
      },
      {
        "index": 17,
        "path": "m/44'/501'/17'",
        "address": "Upn5c7NHC5P362WWm7GpgUM9ihimz8rjGxso5yBXpKN"
      },
      {
        "index": 1024,
        "path": "m/44'/501'/1024'",
        "address": "3oKDWzpr83cbLYwMsqieiFH8HopQGQM56WL47EYfA6w7"
      }
    ]
  },
  {
    "coin": "bitcoincash",
    "hardwareCoin": "bitcoincash",
    "symbol": "BCH",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/145'/0'/0/0",
    "xpub": "xpub6ByHsPNSQXTWZ7PLESMY2FufyYWtLXagSUpMQq7Un96SiThZH2iJB1X7pwviH1WtKVeDP6K8d6xxFzzoaFzF3s8BKCZx8oEDdDkNnp4owAZ",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/145'/0'/0/0",
        "address": "bitcoincash:qqyx49mu0kkn9ftfj6hje6g2wfer34yfnq5tahq3q6"
      },
      {
        "index": 1,
        "path": "m/44'/145'/0'/0/1",
        "address": "bitcoincash:qp8sfdhgjlq68hlzka9lcsxtcnvuvnd0xqxugfzzc5"
      },
      {
        "index": 2,
        "path": "m/44'/145'/0'/0/2",
        "address": "bitcoincash:qqkuy34ntrye9a2h4xpdstcu4aq5wfrwscjtaphenr"
      },
      {
        "index": 17,
        "path": "m/44'/145'/0'/0/17",
        "address": "bitcoincash:qrg7p8vhan69d47aduk89w7u82kpmexjhyllx3hl00"
      },
      {
        "index": 1024,
        "path": "m/44'/145'/0'/0/1024",
        "address": "bitcoincash:qzxmxk05fzrffsvydux2pjzusdr8df53zsrhrhwssx"
      }
    ]
  },
  {
    "coin": "ton",
    "hardwareCoin": "ton",
    "symbol": "TON",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/607'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/607'/0'",
        "address": "UQAzWZa6nM5mJev91wGc7VCSfBoIsYRqKJpV78N8Add9-RKY"
      },
      {
        "index": 1,
        "path": "m/44'/607'/1'",
        "address": "UQDVJucJT96vGh_bYm3e5uzenasiTOwA9orUHQiyhNsKmBrP"
      },
      {
        "index": 2,
        "path": "m/44'/607'/2'",
        "address": "UQBD2j3nVKonc1Fggvmt6W5zgXadnmTl43spUT4BKeTcS4Cx"
      },
      {
        "index": 17,
        "path": "m/44'/607'/17'",
        "address": "UQAvI-2RF7_s-yZPF_7ogI1wv3Fu24HDHN0av-1UVnX5MBeA"
      },
      {
        "index": 1024,
        "path": "m/44'/607'/1024'",
        "address": "UQDCkgRf2-KzH_I3rYMySpUB5WxegPKT5zLg973RPL_SR9rD"
      }
    ]
  },
  {
    "coin": "avalanche",
    "hardwareCoin": "avalanchec",
    "symbol": "AVAX",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x516A2191b53f7654654F209CcA9668b16f149988"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xA3686634A71fbf2366Be4E5a37552Fe99A9fb4b4"
      }
    ]
  },
  {
    "coin": "ethereumclassic",
    "hardwareCoin": "classic",
    "symbol": "ETC",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/61'/0'/0/0",
    "xpub": "xpub6CqTNrUVV95XgHEP2LVe42cdpWKh2jeA4GnyXrQ219wJJVPAFajkbuFEdzf6xfGgdJWrhKuK5p8v47vxVQHFGU85J1J8Di95Z6hFJx8GhYz",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/61'/0'/0/0",
        "address": "0xFA22515E43658ce56A7682B801e9B5456f511420"
      },
      {
        "index": 1,
        "path": "m/44'/61'/0'/0/1",
        "address": "0xAee144cB8098B17BCD98D82802fDf32E8ca518cF"
      },
      {
        "index": 2,
        "path": "m/44'/61'/0'/0/2",
        "address": "0x6A9F90A1241E1960AD1A0cCb2400C9d235eA0846"
      },
      {
        "index": 17,
        "path": "m/44'/61'/0'/0/17",
        "address": "0xC928e5D13f39af17453798aae4c280e739cFA428"
      },
      {
        "index": 1024,
        "path": "m/44'/61'/0'/0/1024",
        "address": "0x500964791E401d88158a1C18C0b900f31F4A19BC"
      }
    ]
  },
  {
    "coin": "litecoin",
    "hardwareCoin": "litecoin",
    "symbol": "LTC",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/84'/2'/0'/0/0",
    "xpub": "zpub6rPo5mF47z5coVm5rvWv7fv181awb7Vckn5Cf3xQXBVKu18kuBHDhNi1Jrb4br6vVD3ZbrnXemEsWJoR18mZwkUdzwD8TQnHDUCGxqZ6swA",
    "addresses": [
      {
        "index": 0,
        "path": "m/84'/2'/0'/0/0",
        "address": "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"
      },
      {
        "index": 1,
        "path": "m/84'/2'/0'/0/1",
        "address": "ltc1qwlezpr3890hcp6vva9twqh27mr6edadreqvhnn"
      },
      {
        "index": 2,
        "path": "m/84'/2'/0'/0/2",
        "address": "ltc1qc6aucuznvhh9uvux246x24vf9y9ncfk729m92s"
      },
      {
        "index": 17,
        "path": "m/84'/2'/0'/0/17",
        "address": "ltc1qvhwp5crr4cr6a2p6hsae4u09nvgdgcegzgxs6f"
      },
      {
        "index": 1024,
        "path": "m/84'/2'/0'/0/1024",
        "address": "ltc1qvg4mue9armagxzfqyr6f28rlxz9q25zk9qyt0z"
      }
    ]
  },
  {
    "coin": "arbitrum",
    "hardwareCoin": "arbitrum",
    "symbol": "ETH",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x516A2191b53f7654654F209CcA9668b16f149988"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xA3686634A71fbf2366Be4E5a37552Fe99A9fb4b4"
      }
    ]
  },
  {
    "coin": "polygon",
    "hardwareCoin": "polygon",
    "symbol": "POL",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6DCoCpSuQZB2jawqnGMEPS63ePKWkwWPH4TU45Q7LPXWuNd8TMtVxRrgjtEshuqpK3mdhaWHPFsBngh5GFZaM6si3yZdUsT8ddYM3PwnATt",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0xb6716976A3ebe8D39aCEB04372f22Ff8e6802D7A"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x516A2191b53f7654654F209CcA9668b16f149988"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xA3686634A71fbf2366Be4E5a37552Fe99A9fb4b4"
      }
    ]
  },
  {
    "coin": "sui",
    "hardwareCoin": "sui",
    "symbol": "SUI",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/784'/0'/0'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/784'/0'/0'/0'",
        "address": "0x5e93a736d04fbb25737aa40bee40171ef79f65fae833749e3c089fe7cc2161f1"
      },
      {
        "index": 1,
        "path": "m/44'/784'/0'/0'/1'",
        "address": "0xf7c7a39996ac7f1c307b96c96d65cce0855dcc7ccd021c453964f2f62f98e71f"
      },
      {
        "index": 2,
        "path": "m/44'/784'/0'/0'/2'",
        "address": "0xd67b0f1c352cb1f3318ef464b04c8fa29aca0eb0574f8c7a4b8a5ea77f9ba455"
      },
      {
        "index": 17,
        "path": "m/44'/784'/0'/0'/17'",
        "address": "0x1a485fa376f04006c12e2a21c04f11b863cba11ccae60d518883d0f2265e027a"
      },
      {
        "index": 1024,
        "path": "m/44'/784'/0'/0'/1024'",
        "address": "0x283408ee3ed53064d32e4637852420b0734a5e59f654855924d8c4a37e56ff82"
      }
    ]
  },
  {
    "coin": "dash",
    "hardwareCoin": "dash",
    "symbol": "DASH",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/5'/0'/0/0",
    "xpub": "xpub6CYEjsU6zPM3sADS2ubu2aZeGxCm3C5KabkCpo4rkNbXGAH9M7rRUJ4E5CKiyUddmRzrSCopPzisTBrXkfCD4o577XKM9mzyZtP1Xdbizyk",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/5'/0'/0/0",
        "address": "XoJA8qE3N2Y3jMLEtZ3vcN42qseZ8LvFf5"
      },
      {
        "index": 1,
        "path": "m/44'/5'/0'/0/1",
        "address": "XbctnEsgWTn5j1co3emZynemxSFPqkLRKZ"
      },
      {
        "index": 2,
        "path": "m/44'/5'/0'/0/2",
        "address": "XdD2biTJ3saZtcR6ravwJ9bvmkvmDq49Xg"
      },
      {
        "index": 17,
        "path": "m/44'/5'/0'/0/17",
        "address": "Xb9f7gpyRMw3ZeqSf84BjYQsmKm4anpHe3"
      },
      {
        "index": 1024,
        "path": "m/44'/5'/0'/0/1024",
        "address": "XvXePSiy73m5rRALUFqERjQjcSoYuUK3KQ"
      }
    ]
  }
]
"""
    private val hiddenJson =
        """
[
  {
    "coin": "bitcoin",
    "hardwareCoin": "bitcoin",
    "symbol": "BTC",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/84'/0'/0'/0/0",
    "xpub": "zpub6rXDN3yuixCtvAyzKn3uWLDr8qXKEQ2orduEL5AAysdHZmuPVUL2vbMQDEhp8L9hRsgM8J8idDNPdZjrob8R5e7x7UoYXVdfjDG96TLa7La",
    "addresses": [
      {
        "index": 0,
        "path": "m/84'/0'/0'/0/0",
        "address": "bc1qv5rmq0kt9yz3pm36wvzct7p3x6mtgehjul0feu"
      },
      {
        "index": 1,
        "path": "m/84'/0'/0'/0/1",
        "address": "bc1qq4w9xyzsylpcy6hd76xr8pecxsvzqe743t2uj0"
      },
      {
        "index": 2,
        "path": "m/84'/0'/0'/0/2",
        "address": "bc1qjakxv37sq70e438vmtx0uw3cc0uesyatx5zx25"
      },
      {
        "index": 17,
        "path": "m/84'/0'/0'/0/17",
        "address": "bc1qnr5lnen20fr0pn332dzxerywq6p7xyxwmw8clr"
      },
      {
        "index": 1024,
        "path": "m/84'/0'/0'/0/1024",
        "address": "bc1q547c5ztj5dpncaza3kaf2k34kfydwjpymqymsc"
      }
    ]
  },
  {
    "coin": "ethereum",
    "hardwareCoin": "ethereum",
    "symbol": "ETH",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6CyAvEUSAz99YVGpjogiwm5bWy6WEgzEzh4AnWaC1ZMdEKkdswsotYYLW95BXHfETUoQC5DmpbjcTdkJbLXdnTVf6BcorfWKniZgmu1v5jX",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x7AF7283bd1462C3b957e8FAc28Dc19cBbF2FAdfe"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0x05f48E30fCb69ADcd2A591Ebc7123be8BE72D7a1"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x179799dE742d22c8A5C4074Ef1EeEEF1175ED412"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xa16B7a4415C284fe4688441dEB6e9ECe6EE9D671"
      }
    ]
  },
  {
    "coin": "tron",
    "hardwareCoin": "tron",
    "symbol": "TRX",
    "decimals": 6,
    "curve": "secp256k1",
    "path": "m/44'/195'/0'/0/0",
    "xpub": "xpub6CmkrrscV3jsY7uKtMA12q3p4szVZiFPi5st5NWJqG5TDqtasZysWAVX3LmwYenemBGwp9YaKQ9jQ2PBKGTmVLWdrByyudsS8H1EX1XfDhU",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/195'/0'/0/0",
        "address": "TAyDUYP5rcf56xFwrg8cU1qQwvnWpkeapM"
      },
      {
        "index": 1,
        "path": "m/44'/195'/0'/0/1",
        "address": "TFYaFtDHPwqLuo64eh4CYJiwHT7UivRmst"
      },
      {
        "index": 2,
        "path": "m/44'/195'/0'/0/2",
        "address": "TGoGHKztj1HpopfSriQtAAaoXwnmaGSEjC"
      },
      {
        "index": 17,
        "path": "m/44'/195'/0'/0/17",
        "address": "TH9id4hFZezBVNB75mkASvZeuTxjQxhrc3"
      },
      {
        "index": 1024,
        "path": "m/44'/195'/0'/0/1024",
        "address": "TEZtwAZhEN1WpZ7ZdH1svYqHw1v7SQo3uQ"
      }
    ]
  },
  {
    "coin": "bsc",
    "hardwareCoin": "smartchain",
    "symbol": "BNB",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6CyAvEUSAz99YVGpjogiwm5bWy6WEgzEzh4AnWaC1ZMdEKkdswsotYYLW95BXHfETUoQC5DmpbjcTdkJbLXdnTVf6BcorfWKniZgmu1v5jX",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x7AF7283bd1462C3b957e8FAc28Dc19cBbF2FAdfe"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0x05f48E30fCb69ADcd2A591Ebc7123be8BE72D7a1"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x179799dE742d22c8A5C4074Ef1EeEEF1175ED412"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xa16B7a4415C284fe4688441dEB6e9ECe6EE9D671"
      }
    ]
  },
  {
    "coin": "ripple",
    "hardwareCoin": "ripple",
    "symbol": "XRP",
    "decimals": 6,
    "curve": "secp256k1",
    "path": "m/44'/144'/0'/0/0",
    "xpub": "xpub6CTm72Hnk5h5NyGMBizfi7JW2ikaqs2jJc1eh37kUYKfJMAgWAFZKfUQZE37Y5fXkSAjLnETgPMWp1Qee2PyXnqt7AGHYxzDXaMFaezwzbf",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/144'/0'/0/0",
        "address": "rfBUajJsZotvNwZz5yUk7GNqcWehxc2YAs"
      },
      {
        "index": 1,
        "path": "m/44'/144'/0'/0/1",
        "address": "rYg9PzpL6QkuKojpc4wscm7QhGCdMZWWR"
      },
      {
        "index": 2,
        "path": "m/44'/144'/0'/0/2",
        "address": "rKszBNki4KLJn8cpY48PZpHM6ebQdNuhsF"
      },
      {
        "index": 17,
        "path": "m/44'/144'/0'/0/17",
        "address": "rpPzeLTS3sBfACPPcSg6ptpaMkTaNFSDmZ"
      },
      {
        "index": 1024,
        "path": "m/44'/144'/0'/0/1024",
        "address": "rGzFA7gW5kFnec4LfESj38Av4wiRAvoNKz"
      }
    ]
  },
  {
    "coin": "dogecoin",
    "hardwareCoin": "doge",
    "symbol": "DOGE",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/3'/0'/0/0",
    "xpub": "dgub8s3rDipXzSGxH4XrwJA2sfJu83D89FWordpJq7uNJmHL87LAFR5Jm95er4g4Wa64yvNNY193By1pFiGMixHYZvyZiftVabMqWK7r1m4TSFC",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/3'/0'/0/0",
        "address": "DMn7J63QSZUR9XNxsUJtvsttZVzV9Am4qM"
      },
      {
        "index": 1,
        "path": "m/44'/3'/0'/0/1",
        "address": "D8r3y9XmpJswSd3Sgk1ZQ2QfWaw41NSLt8"
      },
      {
        "index": 2,
        "path": "m/44'/3'/0'/0/2",
        "address": "DCHvGyMt8hd5diJugxHYJr1dF5phFnTUoc"
      },
      {
        "index": 17,
        "path": "m/44'/3'/0'/0/17",
        "address": "DBn3Q3TCJxf42g7C2M8fLXrzjaRVwncusB"
      },
      {
        "index": 1024,
        "path": "m/44'/3'/0'/0/1024",
        "address": "DJGTmLWmXVAbXhCwhShGVK5VTHj3vuwvwD"
      }
    ]
  },
  {
    "coin": "solana",
    "hardwareCoin": "solana",
    "symbol": "SOL",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/501'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/501'/0'",
        "address": "9md2ZNCYw622wTzgHn71kh9T6mFSoB4USnA4QdvY8oM4"
      },
      {
        "index": 1,
        "path": "m/44'/501'/1'",
        "address": "H6PLX3nyfZA2VM5nd1j11djKTNyLgDhG2uuw6xBpwWBR"
      },
      {
        "index": 2,
        "path": "m/44'/501'/2'",
        "address": "77XxboGTJrFAADS2G4WymZQ7VWd1NWC7UR9d4EK8CCo9"
      },
      {
        "index": 17,
        "path": "m/44'/501'/17'",
        "address": "3jWZDJ9CDmZkQLj1prg6MYgYch6nSxbX3pwGpcRxGqSm"
      },
      {
        "index": 1024,
        "path": "m/44'/501'/1024'",
        "address": "14nEBR2zJNKY7B2EcHHqKJ7PHaC3hwaoPrSzyAdH8Ggr"
      }
    ]
  },
  {
    "coin": "bitcoincash",
    "hardwareCoin": "bitcoincash",
    "symbol": "BCH",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/145'/0'/0/0",
    "xpub": "xpub6DE7kxoaimqfhzJEQJfDmGuS2KSoVWJCsviRtUyZwWmLnPwctNU3dc1iyrNKoGggd4bjCc93MesXJxu74NUwD81g8jvLugVYBe41KD5fMrU",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/145'/0'/0/0",
        "address": "bitcoincash:qpmtwknkc0zfk0j8v6e6p8rye6q48de85stk6qcprd"
      },
      {
        "index": 1,
        "path": "m/44'/145'/0'/0/1",
        "address": "bitcoincash:qz4q4kzwdfc32ejsapzq0uupxca6gj0ym5kawa5zur"
      },
      {
        "index": 2,
        "path": "m/44'/145'/0'/0/2",
        "address": "bitcoincash:qpfyw3k2q6tvae9ej3ddksqrtxphmjtcvg00pmefly"
      },
      {
        "index": 17,
        "path": "m/44'/145'/0'/0/17",
        "address": "bitcoincash:qz63qarkr8lt5lfezskvze68w4vg3gaa5y9cyfuvz3"
      },
      {
        "index": 1024,
        "path": "m/44'/145'/0'/0/1024",
        "address": "bitcoincash:qqatwujsy8fw0w2yepcx8z02yl7er8ll0gfmnna9np"
      }
    ]
  },
  {
    "coin": "ton",
    "hardwareCoin": "ton",
    "symbol": "TON",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/607'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/607'/0'",
        "address": "UQC8Yak9NGmaRtppVSD6FAU8ZKvTRIVBLJ18UP9mc2vwdLUS"
      },
      {
        "index": 1,
        "path": "m/44'/607'/1'",
        "address": "UQDyAtYEkrFti_NE7wQb-7rJS_W9w7VwQcqYdmIRQepWr56T"
      },
      {
        "index": 2,
        "path": "m/44'/607'/2'",
        "address": "UQCTeDUIZjR2EQe1tiu6r7Mkrv3ONM-42mHZ4WWhi0WfB3xg"
      },
      {
        "index": 17,
        "path": "m/44'/607'/17'",
        "address": "UQDUrGz4kSFlq0P-J4WyYS3CrwCyGKAN7Qh93pe6HI-OMfuS"
      },
      {
        "index": 1024,
        "path": "m/44'/607'/1024'",
        "address": "UQAjUvjjeJlzushiXlVE1cW8BSKnKIppHhokmM4KOsO3C8s7"
      }
    ]
  },
  {
    "coin": "avalanche",
    "hardwareCoin": "avalanchec",
    "symbol": "AVAX",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6CyAvEUSAz99YVGpjogiwm5bWy6WEgzEzh4AnWaC1ZMdEKkdswsotYYLW95BXHfETUoQC5DmpbjcTdkJbLXdnTVf6BcorfWKniZgmu1v5jX",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x7AF7283bd1462C3b957e8FAc28Dc19cBbF2FAdfe"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0x05f48E30fCb69ADcd2A591Ebc7123be8BE72D7a1"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x179799dE742d22c8A5C4074Ef1EeEEF1175ED412"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xa16B7a4415C284fe4688441dEB6e9ECe6EE9D671"
      }
    ]
  },
  {
    "coin": "ethereumclassic",
    "hardwareCoin": "classic",
    "symbol": "ETC",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/61'/0'/0/0",
    "xpub": "xpub6DX96SRj5NFFopexCeRQkKtHGSXweLc4ZRPvzBpxMUXK5PsxWuGWfzLqRg2tNuq6BAVYjKEzKmjSBMAthKLXGqF5HTFBpRiXxdamQqBUMPc",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/61'/0'/0/0",
        "address": "0x4b70Ccc980c6c098d3295E545D03726169960Daa"
      },
      {
        "index": 1,
        "path": "m/44'/61'/0'/0/1",
        "address": "0x09267d87039Ad6D970852B1aF8420EadA76D3A1C"
      },
      {
        "index": 2,
        "path": "m/44'/61'/0'/0/2",
        "address": "0xBdd9b22c76f2654A7C635242330EB9b2fA69Efd9"
      },
      {
        "index": 17,
        "path": "m/44'/61'/0'/0/17",
        "address": "0x845648a5fD873eb20Eb3D7Cf24aA2F066A7CA1b0"
      },
      {
        "index": 1024,
        "path": "m/44'/61'/0'/0/1024",
        "address": "0xf8599f25D1A169A47EfC9Cc3010957AC3C15c7f9"
      }
    ]
  },
  {
    "coin": "litecoin",
    "hardwareCoin": "litecoin",
    "symbol": "LTC",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/84'/2'/0'/0/0",
    "xpub": "zpub6rr97kSLwhTthFHsLGYbVzRMT4pfPXPaKKXX2eMcme5a4HsGe378GZj8reb37deZFrXpy6Lv93C43kDrZq9ZTFEz5PCxNASPXJxpUjqKAYZ",
    "addresses": [
      {
        "index": 0,
        "path": "m/84'/2'/0'/0/0",
        "address": "ltc1qmnmdtk82gfvv4khc3w9lrugre34cj65kamf9tz"
      },
      {
        "index": 1,
        "path": "m/84'/2'/0'/0/1",
        "address": "ltc1qxwc6uc7q24vu8njkkgk3a3uzq4xwvak54y8k53"
      },
      {
        "index": 2,
        "path": "m/84'/2'/0'/0/2",
        "address": "ltc1q96mzwdqhj2qxq7z50k28tu2ufl8ftfzaw9ljya"
      },
      {
        "index": 17,
        "path": "m/84'/2'/0'/0/17",
        "address": "ltc1q2z7aveh6lml3wgtstw3hhzp3tr4nnf4yxucgth"
      },
      {
        "index": 1024,
        "path": "m/84'/2'/0'/0/1024",
        "address": "ltc1qzv0wrwa0v7m2848yj8p4cst7c7kcn3jl9lm5sh"
      }
    ]
  },
  {
    "coin": "arbitrum",
    "hardwareCoin": "arbitrum",
    "symbol": "ETH",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6CyAvEUSAz99YVGpjogiwm5bWy6WEgzEzh4AnWaC1ZMdEKkdswsotYYLW95BXHfETUoQC5DmpbjcTdkJbLXdnTVf6BcorfWKniZgmu1v5jX",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x7AF7283bd1462C3b957e8FAc28Dc19cBbF2FAdfe"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0x05f48E30fCb69ADcd2A591Ebc7123be8BE72D7a1"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x179799dE742d22c8A5C4074Ef1EeEEF1175ED412"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xa16B7a4415C284fe4688441dEB6e9ECe6EE9D671"
      }
    ]
  },
  {
    "coin": "polygon",
    "hardwareCoin": "polygon",
    "symbol": "POL",
    "decimals": 18,
    "curve": "secp256k1",
    "path": "m/44'/60'/0'/0/0",
    "xpub": "xpub6CyAvEUSAz99YVGpjogiwm5bWy6WEgzEzh4AnWaC1ZMdEKkdswsotYYLW95BXHfETUoQC5DmpbjcTdkJbLXdnTVf6BcorfWKniZgmu1v5jX",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/60'/0'/0/0",
        "address": "0x9c32F71D4DB8Fb9e1A58B0a80dF79935e7256FA6"
      },
      {
        "index": 1,
        "path": "m/44'/60'/0'/0/1",
        "address": "0x7AF7283bd1462C3b957e8FAc28Dc19cBbF2FAdfe"
      },
      {
        "index": 2,
        "path": "m/44'/60'/0'/0/2",
        "address": "0x05f48E30fCb69ADcd2A591Ebc7123be8BE72D7a1"
      },
      {
        "index": 17,
        "path": "m/44'/60'/0'/0/17",
        "address": "0x179799dE742d22c8A5C4074Ef1EeEEF1175ED412"
      },
      {
        "index": 1024,
        "path": "m/44'/60'/0'/0/1024",
        "address": "0xa16B7a4415C284fe4688441dEB6e9ECe6EE9D671"
      }
    ]
  },
  {
    "coin": "sui",
    "hardwareCoin": "sui",
    "symbol": "SUI",
    "decimals": 9,
    "curve": "ed25519",
    "path": "m/44'/784'/0'/0'/0'",
    "xpub": "",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/784'/0'/0'/0'",
        "address": "0x85614bb760547968e07addd47db5e08c7bebf1e2ed248ff37d9d0ed01b395383"
      },
      {
        "index": 1,
        "path": "m/44'/784'/0'/0'/1'",
        "address": "0x7476e07a779fee4f521e9b88b18b542e925bf4bcf794263a83af64657a08be87"
      },
      {
        "index": 2,
        "path": "m/44'/784'/0'/0'/2'",
        "address": "0x601922fbc240427662e6607eb61f5fac1bb6de67b6472afaac5508089cb45fed"
      },
      {
        "index": 17,
        "path": "m/44'/784'/0'/0'/17'",
        "address": "0x1673f20e37dfc8e4bbbe82c02cb92c751c3c3a2f9660acbf1fd2447eda229969"
      },
      {
        "index": 1024,
        "path": "m/44'/784'/0'/0'/1024'",
        "address": "0x3e24c61d4671ba188d387598d575d759731f10e647fdb367ff7e986e4df6233c"
      }
    ]
  },
  {
    "coin": "dash",
    "hardwareCoin": "dash",
    "symbol": "DASH",
    "decimals": 8,
    "curve": "secp256k1",
    "path": "m/44'/5'/0'/0/0",
    "xpub": "xpub6DUJ48BwJ7E4o8VZSnCCVUeA2C8B361uqypy2Ck2XYVtV9Sb9TgNEtahp51QCkrdcde3esLvn7BEjRyjBX9JsDQB7fNFVSvnvR4KHEKyGYQ",
    "addresses": [
      {
        "index": 0,
        "path": "m/44'/5'/0'/0/0",
        "address": "Xn9KtWHuvMBuSbpzPFTzYG2bC9Wp2ZZJJG"
      },
      {
        "index": 1,
        "path": "m/44'/5'/0'/0/1",
        "address": "XhMCnH6wb7YzNVZFbd3zpvuRcJT48kUxKm"
      },
      {
        "index": 2,
        "path": "m/44'/5'/0'/0/2",
        "address": "XobaWx1tESm1A18T1Yk5GyoXKy3HSnjGCZ"
      },
      {
        "index": 17,
        "path": "m/44'/5'/0'/0/17",
        "address": "Xu5JtL4rUgPAYQYaEBcSTxX1vbP2G5ktKP"
      },
      {
        "index": 1024,
        "path": "m/44'/5'/0'/0/1024",
        "address": "XePoDuzZ9fWMR6XqEtQTZxyrHTP8tuJ6fa"
      }
    ]
  }
]
"""
}
