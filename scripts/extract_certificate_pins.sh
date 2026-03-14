#!/bin/bash

# =============================================================================
# CERTIFICATE PIN EXTRACTION SCRIPT
# =============================================================================
# 
# PURPOSE: Extract SHA-256 certificate pins for production domains
# 
# USAGE: ./extract_certificate_pins.sh
# 
# OUTPUT: SHA-256 pins ready to add to HttpClientProvider.kt
# 
# REQUIREMENTS:
# - OpenSSL installed
# - Internet connection
# 
# =============================================================================

set -e  # Exit on error

echo "============================================="
echo "CERTIFICATE PIN EXTRACTION TOOL"
echo "============================================="
echo ""

# Function to extract certificate pin
extract_pin() {
    local domain=$1
    local port=${2:-443}
    
    echo "Extracting certificate pin for: $domain:$port"
    echo "-------------------------------------------"
    
    # Connect and extract certificate
    local cert_info=$(echo | openssl s_client -connect "$domain:$port" -servername "$domain" 2>/dev/null | openssl x509 -pubkey -noout 2>/dev/null)
    
    if [ -z "$cert_info" ]; then
        echo "❌ FAILED: Could not extract certificate for $domain"
        echo ""
        return 1
    fi
    
    # Extract public key and compute SHA-256 hash
    local pin=$(echo "$cert_info" | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -binary | openssl enc -base64)
    
    if [ -z "$pin" ]; then
        echo "❌ FAILED: Could not compute pin for $domain"
        echo ""
        return 1
    fi
    
    echo "✅ SUCCESS: Certificate pin extracted"
    echo ""
    echo "Add this to HttpClientProvider.kt:"
    echo "  .add(\"$domain\", \"sha256/$pin\")"
    echo ""
    echo "============================================="
    echo ""
}

# =============================================================================
# PRODUCTION DOMAINS
# =============================================================================

echo "Extracting pins for production domains..."
echo ""

# OpenAI API
extract_pin "api.openai.com" 443

# Anthropic API
extract_pin "api.anthropic.com" 443

# Google Gemini API
extract_pin "generativelanguage.googleapis.com" 443

# Hugging Face
extract_pin "huggingface.co" 443

# Tavily API
extract_pin "api.tavily.com" 443

echo ""
echo "============================================="
echo "ALL PINS EXTRACTED SUCCESSFULLY!"
echo "============================================="
echo ""
echo "NEXT STEPS:"
echo "1. Copy the pins above to HttpClientProvider.kt"
echo "2. Add BOTH current pin and backup pin"
echo "3. Test in staging environment first"
echo "4. Deploy to production"
echo ""
echo "IMPORTANT: Keep backup pins for 90 days after certificate rotation!"
echo ""
