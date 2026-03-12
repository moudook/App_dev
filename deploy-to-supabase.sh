#!/bin/bash

# =============================================================================
# SMARTY DATABASE SCHEMA v4.2.0 - DEPLOYMENT SCRIPT
# =============================================================================
# Version: 4.2.0
# Date: March 12, 2026
# Purpose: Automated deployment of database schema v4.2.0 to Supabase
# Usage: ./deploy-to-supabase.sh [OPTIONS]
# =============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCHEMA_FILE="DATABASE_SCHEMA_v4.2.0_OPTIMIZED.sql"
MIGRATION_FILE="MIGRATION_v4.1.0_to_v4.2.0.sql"
BACKUP_PREFIX="backup_$(date +%Y%m%d_%H%M%S)"

# Supabase configuration (set via environment or prompt)
SUPABASE_URL="${SUPABASE_URL:-}"
SUPABASE_ANON_KEY="${SUPABASE_ANON_KEY:-}"

# =============================================================================
# Helper Functions
# =============================================================================

print_header() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

check_prerequisites() {
    print_header "Checking Prerequisites"
    
    # Check if schema file exists
    if [ ! -f "$SCHEMA_FILE" ]; then
        print_error "Schema file not found: $SCHEMA_FILE"
        exit 1
    fi
    print_success "Schema file found: $SCHEMA_FILE"
    
    # Check if migration file exists
    if [ ! -f "$MIGRATION_FILE" ]; then
        print_error "Migration file not found: $MIGRATION_FILE"
        exit 1
    fi
    print_success "Migration file found: $MIGRATION_FILE"
    
    # Check if psql is installed
    if ! command -v psql &> /dev/null; then
        print_error "psql is not installed. Please install PostgreSQL client."
        exit 1
    fi
    print_success "psql is installed"
    
    # Check Supabase credentials
    if [ -z "$SUPABASE_URL" ]; then
        print_warning "SUPABASE_URL not set"
        read -p "Enter Supabase project URL: " SUPABASE_URL
    fi
    
    if [ -z "$SUPABASE_ANON_KEY" ]; then
        print_warning "SUPABASE_ANON_KEY not set"
        read -sp "Enter Supabase anon key: " SUPABASE_ANON_KEY
        echo ""
    fi
    
    print_success "Prerequisites check completed"
    echo ""
}

create_backup() {
    print_header "Creating Database Backup"
    
    BACKUP_FILE="${BACKUP_PREFIX}_backup.sql"
    
    print_warning "This will create a backup of all existing tables..."
    read -p "Continue? (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_warning "Backup cancelled by user"
        return 1
    fi
    
    # Create backup using Supabase dashboard or pg_dump
    print_warning "Manual backup required:"
    echo "1. Go to Supabase Dashboard → Editor"
    echo "2. Run backup queries from $MIGRATION_FILE"
    echo "3. Or use pg_dump for full backup"
    echo ""
    echo "Backup file will be: $BACKUP_FILE"
    echo ""
    
    # Create backup SQL file
    cat > "$BACKUP_FILE" << 'EOF'
-- =============================================================================
-- DATABASE BACKUP
-- =============================================================================
-- Run these queries in Supabase SQL Editor to backup existing data
-- =============================================================================

-- Backup chat sessions
CREATE TABLE IF NOT EXISTS backup_chat_sessions_$(date +%Y%m%d) AS 
SELECT * FROM chat_sessions;

-- Backup chat messages
CREATE TABLE IF NOT EXISTS backup_chat_messages_$(date +%Y%m%d) AS 
SELECT * FROM chat_messages;

-- Backup notes
CREATE TABLE IF NOT EXISTS backup_notes_$(date +%Y%m%d) AS 
SELECT * FROM notes;

-- Backup calendar events
CREATE TABLE IF NOT EXISTS backup_calendar_events_$(date +%Y%m%d) AS 
SELECT * FROM calendar_events;

-- Verify backups
SELECT 'chat_sessions' as table, COUNT(*) as rows FROM backup_chat_sessions_$(date +%Y%m%d)
UNION ALL
SELECT 'chat_messages', COUNT(*) FROM backup_chat_messages_$(date +%Y%m%d)
UNION ALL
SELECT 'notes', COUNT(*) FROM backup_notes_$(date +%Y%m%d)
UNION ALL
SELECT 'calendar_events', COUNT(*) FROM backup_calendar_events_$(date +%Y%m%d);
EOF
    
    print_success "Backup SQL file created: $BACKUP_FILE"
    print_warning "Please run the backup queries in Supabase Dashboard before proceeding"
    
    read -p "Have you completed the backup? (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_error "Backup not completed. Exiting."
        exit 1
    fi
    
    print_success "Backup completed"
    echo ""
}

deploy_schema() {
    print_header "Deploying Database Schema"
    
    DEPLOYMENT_TYPE=$1
    
    if [ "$DEPLOYMENT_TYPE" == "fresh" ]; then
        print_warning "This will create ALL tables from scratch"
        echo "File: $SCHEMA_FILE"
    else
        print_warning "This will MIGRATE existing database to v4.2.0"
        echo "File: $MIGRATION_FILE"
    fi
    
    echo ""
    read -p "Continue with deployment? (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_warning "Deployment cancelled by user"
        exit 0
    fi
    
    print_warning "Opening Supabase SQL Editor..."
    echo ""
    echo "INSTRUCTIONS:"
    echo "1. Copy the entire content of: $DEPLOYMENT_FILE"
    echo "2. Go to: $SUPABASE_URL/project/sql"
    echo "3. Create new query"
    echo "4. Paste the content"
    echo "5. Click 'Run' or press Ctrl+Enter"
    echo "6. Wait for execution (may take 30-60 seconds)"
    echo ""
    
    # Open Supabase dashboard in browser (if possible)
    if command -v xdg-open &> /dev/null; then
        xdg-open "$SUPABASE_URL/project/sql" 2>/dev/null || true
    elif command -v open &> /dev/null; then
        open "$SUPABASE_URL/project/sql" 2>/dev/null || true
    fi
    
    print_warning "Once you've executed the schema, press Enter to continue..."
    read
    
    print_success "Schema deployment initiated"
    echo ""
}

verify_deployment() {
    print_header "Verifying Deployment"
    
    echo "Please run the following verification queries in Supabase SQL Editor:"
    echo ""
    
    cat << 'EOF'
-- =============================================================================
-- DEPLOYMENT VERIFICATION
-- =============================================================================

-- 1. Check table count (should be 28+)
SELECT COUNT(*) as table_count 
FROM information_schema.tables 
WHERE table_schema = 'public';

-- 2. Check junction tables exist
SELECT 'chat_message_notes' as table, COUNT(*) as rows FROM chat_message_notes
UNION ALL
SELECT 'calendar_event_notes', COUNT(*) FROM calendar_event_notes;

-- 3. Check foreign keys (should be 30+)
SELECT COUNT(*) as fk_count 
FROM pg_constraint 
WHERE contype = 'f';

-- 4. Check indexes for junction tables
SELECT indexname 
FROM pg_indexes 
WHERE schemaname = 'public' 
AND (indexname LIKE '%chat_message%' OR indexname LIKE '%calendar_event%');

-- 5. Test junction table insert
INSERT INTO chat_message_notes (message_id, note_id) 
VALUES (gen_random_uuid(), gen_random_uuid());

SELECT 'Test insert successful' as status;

-- 6. Clean up test data
DELETE FROM chat_message_notes 
WHERE message_id NOT IN (SELECT id FROM chat_messages);

-- =============================================================================
-- EXPECTED RESULTS:
-- 1. table_count: 28+
-- 2. Both junction tables should exist
-- 3. fk_count: 30+
-- 4. Multiple indexes should exist
-- 5. Test insert should succeed
-- =============================================================================
EOF
    
    echo ""
    read -p "Did all verification queries pass? (y/n): " -n 1 -r
    echo
    
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        print_error "Verification failed. Please check the errors above."
        exit 1
    fi
    
    print_success "Deployment verified successfully"
    echo ""
}

display_next_steps() {
    print_header "Deployment Complete! Next Steps:"
    
    echo -e "${GREEN}✅ Database schema v4.2.0 deployed successfully${NC}"
    echo ""
    echo "Next steps:"
    echo ""
    echo "1. Deploy Backend Server to Hugging Face:"
    echo "   - Update environment variables"
    echo "   - Push latest code to trigger build"
    echo "   - Verify API endpoints work"
    echo ""
    echo "2. Deploy Mobile App:"
    echo "   - Build release APK"
    echo "   - Test migration locally"
    echo "   - Release to internal testing"
    echo ""
    echo "3. Monitor for 24 hours:"
    echo "   - Watch Supabase logs"
    echo "   - Monitor Hugging Face metrics"
    echo "   - Check Firebase Crashlytics"
    echo ""
    echo "4. Documentation:"
    echo "   - Update API documentation"
    echo "   - Notify team of changes"
    echo "   - Update changelog"
    echo ""
    print_success "See DEPLOYMENT_GUIDE_v4.2.0.md for detailed instructions"
    echo ""
}

# =============================================================================
# Main Script
# =============================================================================

main() {
    print_header "Smarty Database Schema v4.2.0 Deployment"
    
    check_prerequisites
    
    echo "Select deployment type:"
    echo "1) Fresh installation (new database)"
    echo "2) Migration (existing database v4.1.0)"
    echo "3) Verify existing deployment"
    echo ""
    read -p "Enter choice (1-3): " deployment_choice
    
    case $deployment_choice in
        1)
            create_backup
            deploy_schema "fresh"
            verify_deployment
            display_next_steps
            ;;
        2)
            create_backup
            deploy_schema "migration"
            verify_deployment
            display_next_steps
            ;;
        3)
            verify_deployment
            ;;
        *)
            print_error "Invalid choice"
            exit 1
            ;;
    esac
    
    print_success "Deployment script completed successfully!"
    echo ""
    echo -e "${GREEN}🎉 Database schema v4.2.0 is now deployed!${NC}"
}

# Run main function
main "$@"
