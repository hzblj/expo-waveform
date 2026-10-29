Pod::Spec.new do |s|
  s.name           = 'MockMeter'
  s.version        = '0.0.0'
  s.summary        = 'A stand-in microphone for the example'
  s.author         = 'Jan Blazej'
  s.homepage       = 'https://github.com/hzblj/expo-waveform'
  s.license        = 'MIT'
  s.platforms      = { :ios => '16.4' }
  s.swift_version  = '5.9'
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.source_files = '*.swift'
end
