cask "screeaner" do
  version "0.7.2"
  sha256 :no_check

  url "https://github.com/luhno/Screeaner/releases/download/v#{version}/Screeaner-mac.zip"
  name "Screeaner"
  desc "Use an Android tablet as a low-latency second display"
  homepage "https://github.com/luhno/Screeaner"

  depends_on macos: ">= :ventura"

  app "Screeaner.app"

  zap trash: [
    "~/Library/Application Support/Screeaner",
    "~/Library/Preferences/app.screeaner.mac.plist",
  ]
end
