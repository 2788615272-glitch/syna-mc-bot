$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'build\policy-test'
New-Item -ItemType Directory -Force -Path $output | Out-Null

$main = Join-Path $root 'src\main\java\com\syna\bridge\TunnelRevealPolicy.java'
$attention = Join-Path $root 'src\main\java\com\syna\bridge\AttentionPolicy.java'
$entitySafety = Join-Path $root 'src\main\java\com\syna\bridge\HorrorEntitySafety.java'
$storyPacing = Join-Path $root 'src\main\java\com\syna\bridge\StoryPacingPolicy.java'
$storyEntityLedger = Join-Path $root 'src\main\java\com\syna\bridge\StoryEntityLedgerPolicy.java'
$toolGift = Join-Path $root 'src\main\java\com\syna\bridge\ToolGiftPolicy.java'
$fragmentPresentation = Join-Path $root 'src\main\java\com\syna\bridge\FragmentPresentationPolicy.java'
$bridgeConversation = Join-Path $root 'src\main\java\com\syna\bridge\BridgeConversation.java'
$bridgeProtocol = Join-Path $root 'src\main\java\com\syna\bridge\BridgeProtocol.java'
$boredom = Join-Path $root 'src\main\java\com\syna\bridge\SynaBoredomPolicy.java'
$manifestation = Join-Path $root 'src\main\java\com\syna\bridge\ManifestationPolicy.java'
$presenceProof = Join-Path $root 'src\main\java\com\syna\bridge\PresenceProofPolicy.java'
$dangerousSilence = Join-Path $root 'src\main\java\com\syna\bridge\DangerousSilencePolicy.java'
$identityLore = Join-Path $root 'src\main\java\com\syna\bridge\IdentityLorePolicy.java'
$trueName = Join-Path $root 'src\main\java\com\syna\bridge\TrueNameMysteryPolicy.java'
$test = Join-Path $root 'src\test\java\com\syna\bridge\TunnelRevealPolicyRegression.java'

javac -encoding UTF-8 -d $output $main $attention $entitySafety $storyPacing $storyEntityLedger $toolGift $fragmentPresentation $bridgeConversation $bridgeProtocol $boredom $manifestation $presenceProof $dangerousSilence $identityLore $trueName $test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

java -cp $output com.syna.bridge.TunnelRevealPolicyRegression
exit $LASTEXITCODE
