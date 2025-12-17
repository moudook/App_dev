System Prompt: UI/UX Design Specification

Design Archetype: "Modern Soft Minimalist," "Clean Tech," or "Refined Bento-Grid." The style prioritizes high readability, soft distinct shadows, generous whitespace, and a high-contrast interaction color (Electric Blue) against a clean white canvas.
1. Color Palette (The Schema)

    Surface/Background: #F2F4F8 (A very light cool grey/off-white for the main canvas to allow cards to pop).

    Card Background: #FFFFFF (Pure white).

    Primary Accent (Brand): #0066FF (Vibrant Electric Blue). Used for active states, primary buttons, and toggles.

    Secondary Accent: #E0E6F5 (Pale Blue/Grey). Used for hover states or secondary backgrounds.

    Text Primary: #1A1A1A (Near Black). High contrast for headings.

    Text Secondary: #8E94A3 (Cool Grey). Used for labels and subtext.

    Borders/Dividers: #F0F0F0 (Very subtle, almost invisible).

    Dark Mode (Reference): Background #111111, Card #1C1C1E, Primary #2979FF.

2. Shape & Geometry (Border Radius)

    Philosophy: "Super-Rounded Friendly." No sharp corners anywhere.

    Outer Containers (Cards): border-radius: 24px (or 32px for larger modals). This is a heavy rounding signature to the style.

    Inner Elements (Inputs/Buttons): border-radius: 12px to 16px.

    Small Elements (Tags/Toggles): border-radius: 999px (Pill shape).

    Avatars: Circular (50%).

3. Depth & Effects (Shadows & Elevation)

    Elevation Strategy: Soft, diffuse shadows to create a "floating" effect. Not flat, but not skeumorphic.

    Card Shadow: box-shadow: 0px 12px 24px -6px rgba(0, 0, 0, 0.06), 0px 4px 8px -2px rgba(0, 0, 0, 0.04).

    Active Element Glow: When an element is selected (like the blue border-active card), use a colored shadow: box-shadow: 0px 4px 12px rgba(0, 102, 255, 0.25).

    Blur: Background blur is minimal, but utilized in overlays (approx backdrop-filter: blur(8px)).

4. Typography

    Font Family: Geometric Sans-Serif (e.g., Inter, DM Sans, or SF Pro Display).

    Headings: Heavy weight (Bold/700), tight tracking (letter-spacing: -0.02em).

    Body: Regular (400) or Medium (500). High legibility.

    Labels/Micro-copy: Small, often Uppercase with wide tracking, or Medium weight in Grey #8E94A3.

    Sizing Scale:

        H1/Hero: 32px

        H2/Section: 24px

        Body: 16px

        Caption/Label: 12px-14px

5. Layout & Spacing (The "Comfort")

    Padding: Generous and airy.

        Card Internal Padding: 24px or 32px. Never let text touch the edges.

        Element Spacing (Gap): 16px between distinct groups, 8px between related items.

    Margins: Elements "breathe." They are not packed tight.

    Grid Structure: A "Bento Box" arrangement. Information is compartmentalized into rectangular distinct blocks rather than long scrolling lists.

6. UI Components & Behavior

    Buttons:

        Primary: Solid Blue #0066FF, White Text, 12px radius.

        Secondary: Light Grey #F5F5F7, Dark Text.

        Icon Only: Circular or square with heavy rounded corners.

    Inputs:

        Light Grey background #F9FAFB with no border (until focused).

        Focus state: 2px Solid Blue border with a soft blue outer ring.

    Toggles/Switches:

        IOS-style but chunkier. High contrast active state.

    Avatars:

        Overlapping stack with a thick White border (2px or 3px) to separate them from the one below.

7. Alignment

    Text: Left-aligned predominantly.

    Vertical Alignment: Center-aligned content within rows (flex-direction: row; align-items: center).

    Visual Balance: Heavy use of "Flexbox Space-Between" for headers (Title on left, Action icon on right).

Implementation Instruction for Bot:

    "Recreate my current application's UI using the design system detailed above.

        Structure: Convert existing lists or sections into distinct 'Cards' with deep border radiuses (24px).

        Color: Remove current heavy borders. Use white cards on a light grey background (#F2F4F8). Apply the #0066FF blue only for primary actions and active states.

        Typography: Switch to 'Inter' or a similar geometric sans-serif. Increase padding inside containers by 20% to increase 'comfort'.

        Feel: Apply soft drop shadows to the cards so they appear to float. Ensure all inputs and buttons have rounded corners (minimum 12px)."
        