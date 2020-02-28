package com.mayarafelix.friendlychat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.firebase.ui.auth.AuthUI
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference


class MainActivity : AppCompatActivity() {

    val TAG = "MainActivity"
    private val anonymous = "anonymous"
    private val defaultMessageLengthLimit = 1000
    private val RC_SIGN_IN = 123
    private val RC_PHOTO_PICKER = 2

    private lateinit var mMessageListView: ListView
    private lateinit var mMessageAdapter: MessageAdapter
    private lateinit var mProgressBar: ProgressBar
    private lateinit var mPhotoPickerButton: ImageButton
    private lateinit var mMessageEditText: EditText
    private lateinit var mSendButton: Button
    private lateinit var mUsername: String

    private lateinit var firebaseDatabase: FirebaseDatabase
    private lateinit var messageDatabaseReference: DatabaseReference
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var firebaseStorage: FirebaseStorage
    private lateinit var firebaseStorageReference: StorageReference

    private var messageListener: ChildEventListener? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mUsername = anonymous

        initializeFirebaseElements()

        setupViews()

        // Initialize message ListView and its adapter
        val friendlyMessages = ArrayList<FriendlyMessage>()
        mMessageAdapter = MessageAdapter(this, R.layout.item_message, friendlyMessages)
        mMessageListView.adapter = mMessageAdapter

        // Initialize progress bar
        mProgressBar.visibility = ProgressBar.INVISIBLE

        // ImagePickerButton shows an image picker to upload a image for a message
        mPhotoPickerButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            startActivityForResult(
                Intent.createChooser(intent, "Complete action using"),
                RC_PHOTO_PICKER
            )
        }

        // Enable Send button when there's text to send
        mMessageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                mSendButton.isEnabled = charSequence.toString().trim { it <= ' ' }.isNotEmpty()
            }

            override fun afterTextChanged(editable: Editable) {}
        })

        mMessageEditText.filters = arrayOf<InputFilter>(
            InputFilter.LengthFilter(
                defaultMessageLengthLimit
            )
        )

        // Send button sends a message and clears the EditText
        mSendButton.setOnClickListener {
            val friendlyMessage = FriendlyMessage(mMessageEditText.text.toString(), mUsername, null)
            messageDatabaseReference.push().setValue(friendlyMessage);

            // Clear input box
            mMessageEditText.setText("")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                Toast.makeText(this@MainActivity, "Welcome to Friendly Chat", Toast.LENGTH_SHORT).show()
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this@MainActivity, "Sign in Cancelled", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else if (requestCode == RC_PHOTO_PICKER && resultCode == Activity.RESULT_OK) {
            data?.data?.let { selectedUri ->
                selectedUri.lastPathSegment?.let { lastPathSegment ->
                    val storageReference = firebaseStorageReference.child(getFormattedFilePath(lastPathSegment))
                    storageReference.putFile(selectedUri).addOnSuccessListener {
                        storageReference.downloadUrl.addOnSuccessListener { uri ->
                            val friendlyMessage = FriendlyMessage(null, mUsername, uri.toString())
                            messageDatabaseReference.push().setValue(friendlyMessage)
                        }
                    }
                }
            }
        }
    }

    private fun getFormattedFilePath(fullPath: String) : String {
        val pathArray = fullPath.split('/')
        return getFormattedUserName() + "/" + pathArray.get(pathArray.size - 1)
    }

    private fun getFormattedUserName() : String {
        return mUsername.replace(" ", "").toLowerCase()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.sign_out_menu) {
            AuthUI.getInstance().signOut(this)
            return true
        } else {
            return super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onResume() {
        super.onResume()
        attachAuthStateListener()
    }

    override fun onPause() {
        super.onPause()
        detachAuthStateListener()
        detachMessageDatabaseReadListener()
    }

    private fun setupViews() {
        mProgressBar = findViewById(R.id.progressBar)
        mMessageListView = findViewById(R.id.messageListView)
        mPhotoPickerButton = findViewById(R.id.photoPickerButton)
        mMessageEditText = findViewById(R.id.messageEditText)
        mSendButton = findViewById(R.id.sendButton)
    }

    private fun initializeFirebaseElements() {
        firebaseDatabase = FirebaseDatabase.getInstance()
        firebaseAuth = FirebaseAuth.getInstance()
        firebaseStorage = FirebaseStorage.getInstance()

        messageDatabaseReference = firebaseDatabase.reference.child("messages")
        firebaseStorageReference = firebaseStorage.reference.child("chat_photos")
    }

    private fun onSignedInInitialize(username: String) {
        mUsername = username
        attachMessageDatabaseReadListener()
    }

    private fun onSignedOutCleanup() {
        mUsername = anonymous
        detachMessageDatabaseReadListener()
    }

    private fun displaySignInPage() {
        startActivityForResult(
            AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setIsSmartLockEnabled(false)
                .setAvailableProviders(getAvailableProviders())
                .build(),
            RC_SIGN_IN
        )
    }

    private fun getAvailableProviders(): List<AuthUI.IdpConfig> {
        val providers = mutableListOf<AuthUI.IdpConfig>()
        providers.add(AuthUI.IdpConfig.GoogleBuilder().build())
        providers.add(AuthUI.IdpConfig.EmailBuilder().build())

        return providers
    }

    private fun attachMessageDatabaseReadListener() {
        if (messageListener == null) {
            messageListener = object : ChildEventListener {
                override fun onChildAdded(dataSnapshot: DataSnapshot, p1: String?) {
                    val friendlyMessage: FriendlyMessage =
                        dataSnapshot.getValue(FriendlyMessage::class.java) as FriendlyMessage
                    mMessageAdapter.add(friendlyMessage)
                }

                override fun onChildChanged(p0: DataSnapshot, p1: String?) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun onChildRemoved(p0: DataSnapshot) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun onChildMoved(p0: DataSnapshot, p1: String?) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }

                override fun onCancelled(p0: DatabaseError) {
                    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                }
            }
        }

        messageListener?.let {
            messageDatabaseReference.addChildEventListener(it)
        }
    }

    private fun detachMessageDatabaseReadListener() {
        messageListener?.let {
            messageDatabaseReference.removeEventListener(it)
            messageListener = null
        }

        mMessageAdapter.clear()
    }

    private fun attachAuthStateListener() {
        if (authStateListener == null) {
            authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser

                if (user != null && user.displayName != null) {
                    onSignedInInitialize(user.displayName!!)
                } else {
                    onSignedOutCleanup()
                    displaySignInPage()
                }
            }
        }

        authStateListener?.let {
            firebaseAuth.addAuthStateListener(it)
        }
    }

    private fun detachAuthStateListener() {
        authStateListener?.let {
            firebaseAuth.removeAuthStateListener(it)
            authStateListener = null
        }
    }
}
