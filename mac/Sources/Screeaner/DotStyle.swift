import SwiftUI

/// "Screeaner Dot", the same visual language as the tablet app: ink on black,
/// one signal red for what is live or wrong, dots as the unit of measure.
/// Fonts ship in Contents/Resources/Fonts (ATSApplicationFontsPath); without
/// the bundle (`swift run`) they fall back to the system fonts.
enum Dot {
    static let canvas = Color(red: 0.039, green: 0.039, blue: 0.039)        // #0A0A0A
    static let surface = Color(red: 0.090, green: 0.090, blue: 0.090)       // #171717
    static let ink = Color(red: 0.949, green: 0.949, blue: 0.937)           // #F2F2EF
    static let inkSecondary = Color(red: 0.651, green: 0.651, blue: 0.635)  // #A6A6A2
    static let line = ink.opacity(0.10)
    static let lineStrong = ink.opacity(0.30)
    static let signal = Color(red: 0.910, green: 0.208, blue: 0.169)        // #E8352B

    /// Doto: big numbers and the wordmark.
    static func display(_ size: CGFloat) -> Font { .custom("Doto-Black", size: size) }

    /// Space Grotesk: interface text.
    static func text(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        switch weight {
        case .semibold, .bold, .heavy, .black: return .custom("SpaceGrotesk-SemiBold", size: size)
        case .medium: return .custom("SpaceGrotesk-Medium", size: size)
        default: return .custom("SpaceGrotesk-Regular", size: size)
        }
    }

    /// JetBrains Mono: labels and metrics, tabular by nature.
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom(weight == .medium ? "JetBrainsMono-Medium" : "JetBrainsMono-Regular", size: size)
    }
}

/// "01 / RESOLUÇÃO": the numbered section label.
struct DotLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(Dot.mono(10.5))
            .tracking(1.2)
            .foregroundStyle(Dot.inkSecondary)
    }
}

/// A small round marker: ink when fine, signal red when live or wrong.
struct DotMarker: View {
    var color: Color = Dot.ink
    var size: CGFloat = 6

    var body: some View {
        Circle().fill(color).frame(width: size, height: size)
    }
}

/// Segmented control: the selected option is an ink pill on a dark track.
struct DotSegmented<Value: Hashable>: View {
    let options: [(label: String, value: Value)]
    @Binding var selection: Value
    var mono = false

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let selected = option.value == selection
                Button {
                    selection = option.value
                } label: {
                    Text(option.label)
                        .font(mono ? Dot.mono(12, selected ? .medium : .regular) : Dot.text(12.5, selected ? .semibold : .regular))
                        .foregroundStyle(selected ? Dot.canvas : Dot.inkSecondary)
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .background(RoundedRectangle(cornerRadius: 8).fill(selected ? Dot.ink : Color.clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 12).fill(Dot.surface))
    }
}

/// A toggle drawn as a pill with a dot that slides; ink when on.
struct DotToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            HStack {
                configuration.label
                    .font(Dot.text(13))
                    .foregroundStyle(Dot.ink)
                Spacer(minLength: 12)
                ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                    Capsule()
                        .fill(configuration.isOn ? Dot.ink : Color.clear)
                        .overlay(Capsule().stroke(Dot.lineStrong, lineWidth: 1))
                        .frame(width: 38, height: 22)
                    Circle()
                        .fill(configuration.isOn ? Dot.canvas : Dot.inkSecondary)
                        .frame(width: 14, height: 14)
                        .padding(.horizontal, 4)
                }
                .animation(.easeOut(duration: 0.15), value: configuration.isOn)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Outlined pill button.
struct DotButtonStyle: ButtonStyle {
    var primary = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Dot.text(12.5, primary ? .semibold : .medium))
            .foregroundStyle(primary ? Dot.canvas : Dot.ink)
            .padding(.horizontal, 14)
            .frame(minHeight: 28)
            .background(Capsule().fill(primary ? Dot.ink : Color.clear))
            .overlay(Capsule().stroke(primary ? Color.clear : Dot.lineStrong, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
